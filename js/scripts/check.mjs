#!/usr/bin/env node
// build-ui checker: finds uiParameter(...) and uiPanel ... calls in Jenkinsfiles
// and reports mistakes before Jenkins runs them, with Jenkinsfile line numbers.
//
//   pnpm --dir projects/jenkins-ui/js check path/to/Jenkinsfile [more files]
//   node projects/jenkins-ui/js/scripts/check.mjs path/to/Jenkinsfile [more files]
//
// It reads Groovy string literals only. A field built from variables or a
// "${...}" GString cannot be checked and gets a warning instead.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';

const contract = JSON.parse(readFileSync(
  new URL('../../src/main/resources/io/jenkins/plugins/buildui/contract.json', import.meta.url), 'utf8'));

// ---- Groovy scanning ----------------------------------------------------

/**
 * Returns tokens: { type: 'id'|'str'|'num'|'punct', value, start, end, dynamic?, nl? } with comments skipped.
 * nl is true on a token that starts a new line.
 */
export function tokenize(src) {
  const tokens = [];
  let i = 0;
  let nl = false;
  const push = (token) => { if (nl) token.nl = true; nl = false; tokens.push(token); };
  while (i < src.length) {
    const c = src[i];
    if (/\s/.test(c)) { if (c === '\n') nl = true; i++; continue; }
    if (src.startsWith('//', i)) { i = src.indexOf('\n', i); if (i < 0) break; continue; }
    if (src.startsWith('/*', i)) { const e = src.indexOf('*/', i + 2); i = e < 0 ? src.length : e + 2; continue; }
    // Slashy /.../ (only where an operand can start, so a / b stays division) and dollar-slashy $/.../$.
    const prev = tokens[tokens.length - 1];
    const dollar = src.startsWith('$/', i);
    if (dollar || (c === '/' && (!prev || (prev.type === 'punct' && !')]}'.includes(prev.value)) ||
        (prev.type === 'id' && SLASHY_AFTER.has(prev.value))))) {
      const contentStart = i + (dollar ? 2 : 1);
      const close = dollar ? '/$' : '/';
      let j = contentStart;
      let value = '';
      let dynamic = false;
      while (j < src.length && !src.startsWith(close, j)) {
        const esc = dollar ? ['$$', '$/'].find((e) => src.startsWith(e, j)) : src.startsWith('\\/', j) && '\\/';
        if (esc) { value += esc[1]; j += 2; continue; }
        if (src[j] === '$' && /[{a-zA-Z_]/.test(src[j + 1] || '')) dynamic = true;
        value += src[j++];
      }
      push({ type: 'str', value, start: i, contentStart, end: j + close.length, dynamic });
      i = j + close.length;
      continue;
    }
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < src.length && /[\w.]/.test(src[j])) j++;
      push({ type: 'num', value: src.slice(i, j), start: i, end: j });
      i = j;
      continue;
    }
    if (c === "'" || c === '"') {
      const quote = src.startsWith(c.repeat(3), i) ? c.repeat(3) : c;
      const contentStart = i + quote.length;
      let j = contentStart;
      let value = '';
      let dynamic = false;
      const badEscapes = [];
      while (j < src.length && !src.startsWith(quote, j)) {
        if (src[j] === '\\' && j + 1 < src.length) {
          const n = src[j + 1];
          if (n === '\n') { j += 2; continue; } // line continuation
          // Groovy 2.4, which Jenkins runs, accepts only these escapes (checked against groovy-all 2.4.21).
          const unicode = /^u+([0-9a-fA-F]{4})/.exec(src.slice(j + 1, j + 12));
          const octal = /^([0-3][0-7]{0,2}|[4-7][0-7]?)/.exec(src.slice(j + 1, j + 4));
          if (n in SIMPLE_ESCAPES) {
            value += SIMPLE_ESCAPES[n];
            j += 2;
          } else if (unicode) {
            value += String.fromCharCode(parseInt(unicode[1], 16));
            j += 1 + unicode[0].length;
          } else if (octal) {
            value += String.fromCharCode(parseInt(octal[1], 8));
            j += 1 + octal[0].length;
          } else {
            badEscapes.push({ offset: j, escape: '\\' + n });
            value += '\\' + n;
            j += 2;
          }
          continue;
        }
        if (c === '"' && src[j] === '$' && /[{a-zA-Z_]/.test(src[j + 1] || '')) dynamic = true;
        if (quote.length === 1 && src[j] === '\n') break; // unterminated single-line string
        value += src[j++];
      }
      push({ type: 'str', value, start: i, contentStart, end: j + quote.length, dynamic, badEscapes });
      i = j + quote.length;
      continue;
    }
    if (/[A-Za-z_$]/.test(c)) {
      let j = i;
      while (j < src.length && /[\w$]/.test(src[j])) j++;
      push({ type: 'id', value: src.slice(i, j), start: i, end: j });
      i = j;
      continue;
    }
    push({ type: 'punct', value: c, start: i, end: i + 1 });
    i++;
  }
  return tokens;
}

/** Finds calls to `names` and collects their named arguments: { name, start, args: { key: token | { nonLiteral, start } } }. */
export function findCalls(tokens, names) {
  const calls = [];
  for (let k = 0; k < tokens.length; k++) {
    const t = tokens[k];
    if (t.type !== 'id' || !names.includes(t.value)) continue;
    const paren = tokens[k + 1]?.value === '(';
    const commandStyle = tokens[k + 1]?.type === 'id' && tokens[k + 2]?.value === ':';
    if (!paren && !commandStyle) continue;
    const call = { name: t.value, start: t.start, args: {} };
    let m = paren ? k + 2 : k + 1;
    let depth = 0;
    for (; m < tokens.length; m++) {
      const u = tokens[m];
      // A command-style call ends at ';' or at a new line, unless the line
      // break sits after ',' or an operator, or before '.' or '?.'.
      if (!paren && depth === 0 && m > k + 1 && (u.value === ';' ||
          (u.nl && !CONTINUES.has(tokens[m - 1].value) && u.value !== '.' && u.value !== '?'))) break;
      if (u.value === '(' || u.value === '[' || u.value === '{') depth++;
      if (u.value === ')' || u.value === ']' || u.value === '}') {
        if (depth === 0) break;
        depth--;
      }
      if (depth === 0 && u.type === 'id' && tokens[m + 1]?.value === ':') {
        const v = tokens[m + 2];
        // 'a' + b, '''x'''.stripIndent() and the like are not plain literals.
        const plainLiteral = v?.type === 'str' && !OPERATORS.has(tokens[m + 3]?.value);
        call.args[u.value] = plainLiteral ? v : { nonLiteral: true, start: u.start };
        m += 2;
      }
    }
    calls.push(call);
    // A command-style call stops on the first token of the next statement; scan that token again.
    k = paren ? m : m - 1;
  }
  return calls;
}

const SLASHY_AFTER = new Set(['return', 'case', 'in', 'assert']);
const SIMPLE_ESCAPES = { b: '\b', t: '\t', n: '\n', f: '\f', r: '\r', '\\': '\\', "'": "'", '"': '"', $: '$' };
const OPERATORS = new Set(['+', '.', '?', '*', '-', '[', '|', '&']);
const CONTINUES = new Set([',', ':', '=', '+', '-', '*', '/', '%', '.', '?', '|', '&', '<', '>', '!', '~', '^']);
const lineOf = (src, offset) => src.slice(0, offset).split('\n').length;
const columnOf = (src, offset) => offset - src.lastIndexOf('\n', offset - 1);

// ---- checks -------------------------------------------------------------

function checkSchema(schema, at, report) {
  if (typeof schema === 'boolean') return;
  if (schema === null || typeof schema !== 'object' || Array.isArray(schema)) {
    report('error', `schema${at ? ' at ' + at : ''}: a schema must be an object or a boolean`);
    return;
  }
  for (const [key, value] of Object.entries(schema)) {
    if (key === 'x-git-ref' && value !== true && !['branch', 'tag', 'any'].includes(value)) {
      report('error', `schema${at ? ' at ' + at : ''}: x-git-ref must be true, "branch", "tag" or "any"`);
    }
    if (contract.schemaAnnotations.includes(key) || key.startsWith('x-')) continue;
    if (!contract.schemaValidations.includes(key)) {
      report('error', `schema${at ? ' at ' + at : ''}: keyword '${key}' is not supported; supported: ${contract.schemaValidations.join(', ')}`);
      continue;
    }
    if (key === 'type') {
      for (const t of [].concat(value)) {
        if (!contract.schemaTypes.includes(t)) report('error', `schema${at ? ' at ' + at : ''}: unknown type ${JSON.stringify(t)}`);
      }
    } else if (key === 'properties' && value && typeof value === 'object') {
      for (const [name, sub] of Object.entries(value)) checkSchema(sub, `${at}/properties/${name}`, report);
    } else if (key === 'items' || key === 'additionalProperties') {
      checkSchema(value, `${at}/${key}`, report);
    } else if (key === 'pattern' && typeof value === 'string' && value.length > contract.maxPatternLength) {
      report('error', `schema${at ? ' at ' + at : ''}: pattern is longer than ${contract.maxPatternLength}`);
    }
  }
}

function parseJson(field, text, report) {
  try {
    return JSON.parse(text);
  } catch (e) {
    report('error', `${field} is not valid JSON: ${e.message}`);
    return undefined;
  }
}

async function checkModule(text, report, reportAt) {
  const imports = [];
  let result;
  try {
    result = await build({
      stdin: { contents: text, loader: 'js', sourcefile: 'module.mjs' },
      bundle: true, write: false, format: 'esm', metafile: true, logLevel: 'silent',
      plugins: [{
        name: 'no-imports',
        setup(b) {
          b.onResolve({ filter: /.*/ }, (args) => {
            imports.push(args.path);
            return { path: args.path, external: true };
          });
        },
      }],
    });
  } catch (e) {
    for (const err of e.errors || [e]) {
      if (err.location) reportAt('error', err.location.line, err.location.column + 1, `module: ${err.text}`);
      else report('error', `module: ${err.text || err.message}`);
    }
    return;
  }
  for (const spec of imports) {
    report('error', `module imports '${spec}'; job code gets its libraries through runtime, so the page needs no import map`);
  }
  const output = Object.values(result.metafile.outputs)[0];
  if (!output?.exports?.includes('default')) {
    report('error', 'module has no default export; it must export default function mount({ root, ui, runtime, signal })');
  }
}

function checkHtml(html, report) {
  if (/<script[\s>]/i.test(html)) report('error', 'html contains <script>; scripts in html never run, put code in module');
  const handler = /<[a-z][^>]*?\s(on[a-z]+)\s*=/gi;
  for (const m of html.matchAll(handler)) {
    report('error', `html uses the inline handler ${m[1]}=; Jenkins's CSP blocks it, attach the handler in module`);
  }
}

const NO_CALLS = 'no uiParameter or uiPanel call found';

/** Checks one Jenkinsfile's text; returns [{ level, line, column, message }]. */
export async function checkSource(src) {
  const problems = [];
  const tokens = tokenize(src);
  // Groovy refuses to compile the whole file over one of these, wherever it is.
  for (const t of tokens) {
    for (const { offset, escape } of t.badEscapes ?? []) {
      problems.push({ level: 'error', line: lineOf(src, offset), column: columnOf(src, offset),
        message: `${escape} is not a Groovy escape; write \\${escape}` });
    }
  }
  const calls = findCalls(tokens, ['uiParameter', 'uiPanel']);
  for (const call of calls) {
    const callLine = lineOf(src, call.start);
    const label = `${call.name}${call.args.name?.value ? ` '${call.args.name.value}'` : call.args.title?.value ? ` '${call.args.title.value}'` : ''}`;
    const report = (level, message, offset = call.start) =>
      problems.push({ level, line: lineOf(src, offset), column: columnOf(src, offset), message: `${label}: ${message}` });
    const field = (key) => {
      if (!(key in call.args)) return undefined;
      const token = call.args[key];
      if (token.nonLiteral) {
        report('warning', `${key} is not a plain string literal, so it was not checked`, token.start);
        return undefined;
      }
      if (token.dynamic) {
        report('warning', `${key} is a "..." GString with \${...}; use '...' or '''...''' so the value is checked and fixed`, token.start);
        return undefined;
      }
      return token;
    };

    const nameKey = call.name === 'uiParameter' ? 'name' : 'title';
    if (!(nameKey in call.args)) report('error', `${nameKey} is required`);
    const runtimeToken = field('runtime');
    const runtime = runtimeToken ? runtimeToken.value : 'preact11-htm3';
    if (runtimeToken && !contract.runtimes.includes(runtime)) {
      report('error', `unknown runtime '${runtime}'; available: ${contract.runtimes.join(', ')}`, runtimeToken.start);
    }

    const schemaToken = field('schema');
    let schema;
    if (schemaToken) {
      const at = (level, message) => report(level, message, schemaToken.start);
      schema = parseJson('schema', schemaToken.value, at);
      if (schema !== undefined) checkSchema(schema, '', at);
    }
    for (const key of ['defaultJson', 'data']) {
      const token = field(key);
      if (token) parseJson(key, token.value, (level, message) => report(level, message, token.start));
    }
    const htmlToken = field('html');
    if (htmlToken) checkHtml(htmlToken.value, (level, message) => report(level, message, htmlToken.start));
    field('css');

    const moduleToken = field('module');
    if (moduleToken) {
      const base = lineOf(src, moduleToken.contentStart);
      await checkModule(moduleToken.value, (level, message) => report(level, message, moduleToken.start),
        (level, line, column, message) => problems.push({ level, line: base + line - 1, column, message: `${label}: ${message}` }));
    } else if (!('module' in call.args) && runtime !== 'schema-form' && call.name === 'uiParameter') {
      report('error', `module is required unless runtime is 'schema-form'`);
    }
    if (runtime === 'schema-form' && call.name === 'uiParameter' && schema !== undefined && schema?.type !== 'object') {
      report('error', `runtime 'schema-form' needs a schema whose type is "object"`, schemaToken?.start);
    }
  }
  if (calls.length === 0) problems.push({ level: 'warning', line: 1, column: 1, message: NO_CALLS });
  return problems.sort((a, b) => a.line - b.line || a.column - b.column);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const files = process.argv.slice(2);
  if (files.length === 0) {
    console.error('usage: check.mjs <Jenkinsfile> [...]');
    process.exit(2);
  }
  // `pnpm --dir js check <file>` runs this in js/; pnpm puts the caller's directory in INIT_CWD.
  const base = process.env.INIT_CWD ?? process.cwd();
  let errors = 0;
  let empty = 0; // a file with no call to check usually means the scan went wrong
  for (const file of files) {
    let src;
    try {
      src = readFileSync(path.resolve(base, file), 'utf8');
    } catch (e) {
      console.error(`${file}: cannot read: ${e.code ?? e.message}`);
      errors++;
      continue;
    }
    for (const p of await checkSource(src)) {
      if (p.level === 'error') errors++;
      if (p.message === NO_CALLS) empty++;
      console.log(`${file}:${p.line}:${p.column}: ${p.level}: ${p.message}`);
    }
  }
  if (empty) console.log(`${empty} of ${files.length} file(s) had nothing to check`);
  else if (errors === 0) console.log(`${files.length} file(s) checked, no errors`);
  process.exit(errors || empty ? 1 : 0);
}
