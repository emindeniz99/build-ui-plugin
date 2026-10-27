#!/usr/bin/env node
// build-ui checker: finds uiParameter(...) and uiPanel ... calls in Jenkinsfiles
// and reports mistakes before Jenkins runs them, with Jenkinsfile line numbers.
//
//   pnpm --dir js check path/to/Jenkinsfile [more files]
//   node js/scripts/check.mjs path/to/Jenkinsfile [more files]
//
// It reads Groovy string literals only. A field built from variables or a
// "${...}" GString cannot be checked and gets a warning instead.
import { readFileSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { build } from 'esbuild';

export const contract = JSON.parse(readFileSync(
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

/** Finds calls to `names` and collects their named arguments: { name, start, args: { key: token | { nonLiteral, start } } }.
 * A plain `true` or `false` becomes { type: 'bool', value, start }. */
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
        const plain = !OPERATORS.has(tokens[m + 3]?.value);
        if (plain && v?.type === 'str') call.args[u.value] = v;
        else if (plain && v?.type === 'id' && (v.value === 'true' || v.value === 'false')) call.args[u.value] = { type: 'bool', value: v.value === 'true', start: v.start };
        else call.args[u.value] = { nonLiteral: true, start: u.start };
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

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

// Mirrors Schema.check in the controller, which rejects these when the job is configured.
export function checkSchema(schema, at, report) {
  if (typeof schema === 'boolean') return;
  const where = `schema${at ? ' at ' + at : ''}`;
  if (!isObject(schema)) {
    report('error', `${where}: a schema must be an object or a boolean`);
    return;
  }
  for (const [key, value] of Object.entries(schema)) {
    if (key === 'x-git-ref' && value !== true && !['branch', 'tag', 'any'].includes(value)) {
      report('error', `${where}: x-git-ref must be true, "branch", "tag" or "any"`);
    }
    if (key === 'x-git-repository') {
      if (typeof value !== 'string' || value.length === 0 || value.length > 100) {
        report('error', `${where}: x-git-repository must be a glob of a repository URL, 1 to 100 characters`);
      } else if (!('x-git-ref' in schema)) {
        report('error', `${where}: x-git-repository needs x-git-ref next to it`);
      }
    }
    if (contract.schemaAnnotations.includes(key) || key.startsWith('x-')) continue;
    if (!contract.schemaValidations.includes(key)) {
      report('error', `${where}: keyword '${key}' is not supported; supported: ${contract.schemaValidations.join(', ')}`);
      continue;
    }
    if (key === 'type') {
      for (const t of [].concat(value)) {
        if (!contract.schemaTypes.includes(t)) report('error', `${where}: unknown type ${JSON.stringify(t)}`);
      }
    } else if (key === 'enum') {
      if (!Array.isArray(value) || value.length === 0) report('error', `${where}: enum must be a non-empty array`);
    } else if (key === 'required') {
      if (!Array.isArray(value) || value.some((n) => typeof n !== 'string')) report('error', `${where}: required must be an array of names`);
    } else if (key === 'properties') {
      if (!isObject(value)) report('error', `${where}: properties must be an object`);
      else for (const [name, sub] of Object.entries(value)) checkSchema(sub, `${at}/properties/${name}`, report);
    } else if (key === 'items' || key === 'additionalProperties') {
      checkSchema(value, `${at}/${key}`, report);
    } else if (key === 'pattern') {
      if (typeof value !== 'string') report('error', `${where}: pattern must be a string`);
      else if (value.length > contract.maxPatternLength) report('error', `${where}: pattern is longer than ${contract.maxPatternLength}`);
      else {
        // The controller compiles a Java regex; JavaScript rejects some valid Java syntax, such as (?i), so this only warns.
        try { new RegExp(value); } catch (e) {
          report('warning', `${where}: pattern does not compile as a JavaScript regular expression; the controller rejects it if Java cannot compile it either (${e.message})`);
        }
      }
    } else if (key === 'uniqueItems') {
      if (typeof value !== 'boolean') report('error', `${where}: uniqueItems must be a boolean`);
    } else if (key !== 'const' && typeof value !== 'number') {
      report('error', `${where}: ${key} must be a number`);
    }
  }
}

// The controller's limits (Json.java); a value over them is refused when the job is configured or run.
const JSON_MAX_CHARS = 256 * 1024;
const JSON_MAX_DEPTH = 32;
const JSON_MAX_ITEMS = 1000;

export function parseJson(field, text, report) {
  if (text.length > JSON_MAX_CHARS) {
    report('error', `${field} is ${text.length} characters; the limit is ${JSON_MAX_CHARS}`);
    return undefined;
  }
  let value;
  try {
    value = JSON.parse(text);
  } catch (e) {
    report('error', `${field} is not valid JSON: ${e.message}`);
    return undefined;
  }
  // JSON.parse keeps the last of two equal keys and has no depth limit; scan the (valid) text for both.
  const open = [];
  let depth = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '{' || c === '[') {
      open.push(c === '{' ? new Set() : null);
      depth = Math.max(depth, open.length);
    } else if (c === '}' || c === ']') {
      open.pop();
    } else if (c === '"') {
      let j = i + 1;
      while (text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      let k = j + 1;
      while (/\s/.test(text[k] ?? '')) k++;
      const keys = open[open.length - 1];
      if (keys && text[k] === ':') {
        const key = JSON.parse(text.slice(i, j + 1));
        if (keys.has(key)) report('error', `${field} has the key ${JSON.stringify(key)} twice in one object`);
        keys.add(key);
      }
      i = j;
    }
  }
  if (depth > JSON_MAX_DEPTH) {
    report('error', `${field} is nested deeper than ${JSON_MAX_DEPTH} levels`);
    return undefined;
  }
  const count = (v) => {
    if (Array.isArray(v) && v.length > JSON_MAX_ITEMS) {
      report('error', `${field} has an array with ${v.length} items; the limit is ${JSON_MAX_ITEMS}`);
    } else if (isObject(v) && Object.keys(v).length > JSON_MAX_ITEMS) {
      report('error', `${field} has an object with ${Object.keys(v).length} properties; the limit is ${JSON_MAX_ITEMS}`);
    }
    if (v !== null && typeof v === 'object') Object.values(v).forEach(count);
  };
  count(value);
  return value;
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
  // A panel's html is not split, so its scripts and styles stay in the markup the browser reads.
  const scan = scanHandlers(html, [...TEXT_ELEMENTS, 'script', 'style']);
  for (const name of scan.handlers) {
    report('error', `html uses the inline handler ${name}=; Jenkins's CSP blocks it, attach the handler in module`);
  }
  if (scan.hidden) report('error', hiddenEnd('html', scan.hidden));
}

const HANDLER = /^on[a-z]+$/i;
const SRC = /(?:^|[\s/])src(?:\s*=|[\s/]|$)/i;
const MODULE_TYPE = /(?:^|[\s/])type\s*=\s*(?:"\s*module\s*"|'\s*module\s*'|module(?=[\s/]|$))/i;
// Elements whose content HTML reads as text up to their end tag, while inside SVG or MathML the
// same content is markup. The handler check reads it as markup and refuses a tag or comment that
// hides the end tag, so both readings see the same tags. Mirrors SingleFileHtml.java.
const TEXT_ELEMENTS = ['textarea', 'title', 'noscript', 'iframe', 'noembed', 'noframes', 'xmp'];

const isSpace = (c) => c === ' ' || c === '\t' || c === '\n' || c === '\f' || c === '\r';
const isLetter = (c) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
const asciiLower = (s) => s.replace(/[A-Z]/g, (c) => c.toLowerCase());
const hiddenEnd = (what, name) => `${what} has </${name} inside a tag or comment; a browser ends the <${name}> there`
  + ' and reads what follows as markup, so move it out of the tag or comment';

/**
 * The markup that starts at the '<' at lt, read as the HTML tokenizer reads it:
 * { end, startTag, handler }. end is exclusive, -1 when the text ends inside a
 * tag, lt + 1 when the '<' is text. A start tag has its lowercase name and its
 * first on*= attribute.
 */
function construct(html, lt) {
  const n = html.length;
  const next = html[lt + 1] ?? '';
  if (isLetter(next)) return tag(html, lt + 1, true);
  if (html.startsWith('<!--', lt)) {
    // "<!-->" and "<!--->" are whole comments, and "--!>" ends one too.
    const dashes = html.indexOf('-->', lt + 2);
    const bang = html.indexOf('--!>', lt + 4);
    const end = dashes >= 0 && (bang < 0 || dashes < bang) ? dashes + 3 : bang >= 0 ? bang + 4 : n;
    return { end };
  }
  if (next === '/' && isLetter(html[lt + 2] ?? '')) return tag(html, lt + 2, false);
  if (next === '!' || next === '?' || next === '/') {
    // A doctype or a bogus comment, "</>" included: it ends at the first '>'.
    const gt = html.indexOf('>', lt + 2);
    return { end: gt < 0 ? n : gt + 1 };
  }
  return { end: lt + 1 };
}

// A tag from its name at `at` to its '>': a quoted value may hold '>', and '/' separates attributes.
function tag(html, at, start) {
  const n = html.length;
  let i = at;
  while (i < n && !isSpace(html[i]) && html[i] !== '/' && html[i] !== '>') i++;
  const name = asciiLower(html.slice(at, i));
  let handler = null;
  while (i < n) {
    const c = html[i];
    if (c === '>') return start ? { end: i + 1, startTag: name, handler } : { end: i + 1 };
    if (isSpace(c) || c === '/') { i++; continue; }
    // An attribute name runs to a space, '/', '>' or '='; a '=' in first place belongs to it.
    const nameStart = i++;
    while (i < n && !isSpace(html[i]) && !'/>='.includes(html[i])) i++;
    const attribute = html.slice(nameStart, i);
    while (i < n && isSpace(html[i])) i++;
    if (i >= n || html[i] !== '=') continue;
    if (handler === null && HANDLER.test(attribute)) handler = attribute;
    i++;
    while (i < n && isSpace(html[i])) i++;
    if (html[i] === '"' || html[i] === "'") {
      const quote = html.indexOf(html[i], i + 1);
      if (quote < 0) return { end: -1 };
      // The next attribute may follow the quote without a space.
      i = quote + 1;
    } else {
      while (i < n && !isSpace(html[i]) && html[i] !== '>') i++;
    }
  }
  return { end: -1 };
}

/**
 * The on*= attributes of the start tags a browser reads in html, and the name
 * of a text element whose end tag a tag or comment hides ({ handlers, hidden }).
 * HTML ends a text element at the first </name followed by a space, '/' or
 * '>'; each name's search only moves forward, so the scan stays linear.
 */
function scanHandlers(html, textElements) {
  const ends = new Map(); // name -> { end, searched, open }
  const handlers = [];
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf('<', i);
    if (lt < 0) break;
    const c = construct(html, lt);
    const end = c.end < 0 ? html.length : c.end;
    for (const [name, e] of ends) {
      if (!e.open) continue;
      if (e.end <= lt) e.open = false;
      else if (e.end < end) return { handlers, hidden: name };
    }
    if (c.handler) handlers.push(c.handler);
    if (c.startTag && textElements.includes(c.startTag)) {
      const e = ends.get(c.startTag) ?? { end: -1, searched: 0, open: false };
      ends.set(c.startTag, e);
      if (e.end < end && e.searched < html.length) {
        e.end = -1;
        for (let j = Math.max(end, e.searched); (j = html.indexOf('</', j)) >= 0; j += 2) {
          const after = html[j + 2 + c.startTag.length] ?? '';
          if (asciiLower(html.slice(j + 2, j + 2 + c.startTag.length)) === c.startTag && (isSpace(after) || after === '/' || after === '>')) {
            e.end = j;
            break;
          }
        }
        e.searched = e.end < 0 ? html.length : e.end;
      }
      e.open = e.end >= 0;
    }
    i = end;
  }
  return { handlers, hidden: null };
}

/**
 * Splits a uiParameter's html the way the controller does (SingleFileHtml.java):
 * one <script type="module"> becomes the module, every <style> becomes css, the
 * rest stays html. A script ends at the first </script and a style at the first
 * </style, as in HTML. Comments and tags are read as the HTML tokenizer reads
 * them, and the html that is left is checked for inline handlers. Returns
 * { module, moduleStart, html, css, errors }.
 */
export function splitHtml(html, what) {
  const scriptErrors = [];
  const handlerErrors = [];
  let module = null;
  let moduleStart = -1;
  const css = [];
  let rest = '';
  let i = 0;
  while (i < html.length) {
    const lt = html.indexOf('<', i);
    if (lt < 0) { rest += html.slice(i); break; }
    rest += html.slice(i, lt);
    const c = construct(html, lt);
    // The text ends inside a tag; the browser drops it.
    if (c.end < 0) { rest += html.slice(lt); break; }
    const name = c.startTag;
    if (name !== 'script' && name !== 'style') { rest += html.slice(lt, c.end); i = c.end; continue; }
    // rest ends with a '<' read as text exactly when one stands right before this tag
    // (reading rest itself would flatten the string on every element).
    if (html[lt - 1] === '<') {
      scriptErrors.push(`${what} has a '<' right before <${name}>; taking the element out would join it`
        + ' to the text after the element, so write it as &lt;');
    }
    const gt = c.end - 1;
    const startTag = html.slice(lt, gt + 1);
    const closeRe = new RegExp(`</${name}`, 'ig');
    closeRe.lastIndex = gt + 1;
    const close = closeRe.exec(html)?.index ?? -1;
    if (close < 0) { scriptErrors.push(`${what} has a <${name}> without </${name}>`); break; }
    const content = html.slice(gt + 1, close);
    const closeEnd = html.indexOf('>', close);
    i = closeEnd < 0 ? html.length : closeEnd + 1;
    if (name === 'style') { css.push(content); continue; }
    const attributes = startTag.slice('<script'.length, -1);
    if (SRC.test(attributes)) {
      scriptErrors.push(`${what} has a <script src=...>; a form loads no other code, put the code in the module script`);
    } else if (!MODULE_TYPE.test(attributes)) {
      scriptErrors.push(`${what} has a <script> without type="module"; Jenkins's CSP blocks inline scripts,`
        + ' so write the form\'s code as one <script type="module"> that exports default function mount');
    } else if (module !== null) {
      scriptErrors.push(`${what} has more than one <script type="module">; a form has one module`);
    } else {
      module = content;
      moduleStart = gt + 1;
    }
  }
  const scan = scanHandlers(rest, TEXT_ELEMENTS);
  for (const name of scan.handlers) {
    handlerErrors.push(`${what} uses the inline handler ${name}=; Jenkins's CSP blocks it, attach the handler in the module`);
  }
  if (scan.hidden) handlerErrors.push(hiddenEnd(what, scan.hidden));
  const blank = (s) => s === null || s.trim() === '';
  return {
    module: blank(module) ? null : module,
    moduleStart,
    html: blank(rest) ? null : rest,
    css: css.length && !blank(css.join('\n')) ? css.join('\n') : null,
    errors: [...scriptErrors, ...handlerErrors],
  };
}

// Mirrors FormFrom.java.
const FORM_FROM_MAX_CHARS = 200;
const FORM_FROM_MAX_CODE_BYTES = 512 * 1024;
const FORM_FROM_FIELDS = ['module', 'html', 'css', 'schema', 'defaultJson'];

export function formFromPathError(p) {
  if (p.length > FORM_FROM_MAX_CHARS) return `formFrom is ${p.length} characters; the limit is ${FORM_FROM_MAX_CHARS}`;
  if (!/^[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*$/.test(p)) {
    return `formFrom '${p}' must be a folder relative to the repository root: letters, digits, '.', '_' and '-' separated by '/',`
      + " with no leading or trailing '/' and no '\\'";
  }
  const dots = p.split('/').find((s) => s === '.' || s === '..');
  return dots ? `formFrom '${p}' must not contain '${dots}'` : null;
}

const FORM_FROM_FILES = [['form.mjs', FORM_FROM_MAX_CODE_BYTES], ['form.html', FORM_FROM_MAX_CODE_BYTES],
  ['form.css', FORM_FROM_MAX_CODE_BYTES], ['schema.json', JSON_MAX_CHARS], ['default.json', JSON_MAX_CHARS]];

/**
 * Reads a formFrom folder with the controller's size limits. Returns null when
 * the folder does not exist, else { files, errors }: files maps each of the five
 * file names to its text, or null when it is missing, blank or too large;
 * errors are [{ file, message }].
 */
export function readFormFolder(root, rel) {
  const dir = path.join(root, rel);
  let isDir = false;
  try { isDir = statSync(dir).isDirectory(); } catch { /* missing */ }
  if (!isDir) return null;
  const files = {};
  const errors = [];
  for (const [file, limit] of FORM_FROM_FILES) {
    let text = null;
    try { text = readFileSync(path.join(dir, file), 'utf8'); } catch (e) {
      if (e.code !== 'ENOENT') throw e;
    }
    if (text !== null && Buffer.byteLength(text) > limit) {
      errors.push({ file, message: `larger than ${limit / 1024} KiB; the controller refuses it` });
      text = null;
    }
    files[file] = text === null || text.trim() === '' ? null : text;
  }
  return { files, errors };
}

/** The repository root for formFrom paths: the Git top level of `file`, else its directory. */
export function repoRoot(file) {
  const git = spawnSync('git', ['-C', path.dirname(file), 'rev-parse', '--show-toplevel'], { encoding: 'utf8' });
  return git.status === 0 ? git.stdout.trim() : path.dirname(file);
}

/** Checks a formFrom folder on disk like inline fields; problems carry the file name. */
async function checkFolder(root, rel, runtime, report) {
  const folder = readFormFolder(root, rel);
  if (!folder) {
    report('warning', `formFrom folder '${rel}' is not under ${root}, so its files were not checked; Jenkins reads it from the branch head`);
    return;
  }
  const at = (file) => (level, message) => report(level, `${rel}/${file}: ${message}`);
  for (const { file, message } of folder.errors) at(file)('error', message);
  const { 'form.mjs': mjs, 'form.html': html, 'schema.json': schemaText, 'default.json': defaultText } = folder.files;

  let htmlModule = null;
  if (html !== null) {
    const parts = splitHtml(html, 'form.html');
    for (const message of parts.errors) report('error', `${rel}/${message}`);
    if (parts.module !== null && mjs !== null) {
      report('error', `${rel}/form.html has a <script type="module"> and the form has form.mjs too; keep one of them`);
    }
    if (parts.module !== null) htmlModule = { text: parts.module, line: lineOf(html, parts.moduleStart) };
  }
  const moduleFile = mjs !== null ? { file: 'form.mjs', text: mjs, line: 1 } : htmlModule && { file: 'form.html', ...htmlModule };
  if (moduleFile) {
    await checkModule(moduleFile.text, at(moduleFile.file), (level, line, column, message) =>
      report(level, `${rel}/${moduleFile.file}:${moduleFile.line + line - 1}:${column}: ${message}`));
  } else if (runtime !== 'schema-form') {
    report('error', `${rel} has no form.mjs and no <script type="module"> in form.html; a form needs a module unless runtime is 'schema-form'`);
  }
  let schema;
  if (schemaText !== null) {
    schema = parseJson('schema.json', schemaText, at('schema.json'));
    if (schema !== undefined) checkSchema(schema, '', at('schema.json'));
  }
  if (defaultText !== null) parseJson('default.json', defaultText, at('default.json'));
  if (runtime === 'schema-form' && schema?.type !== 'object') {
    report('error', `runtime 'schema-form' needs ${rel}/schema.json with "type": "object"`);
  }
}

const NO_CALLS = 'no uiParameter or uiPanel call found';

/**
 * Checks one Jenkinsfile's text; returns [{ level, line, column, message }].
 * With `root` (the repository root on disk), a formFrom folder found under it
 * is checked like inline fields.
 */
export async function checkSource(src, { root } = {}) {
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
      if (token.nonLiteral || token.type === 'bool') {
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
    } else if (contract.evalRuntimes.includes(runtime)) {
      report('warning', `runtime '${runtime}' needs eval; it runs only if an administrator allows runtimes that need eval,`
        + ' and it stops working when Jenkins enforces its Content Security Policy. alpine3-csp, preact11-htm3 and schema-form need no eval',
      runtimeToken.start);
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
    let htmlModule = null;
    if (htmlToken && call.name === 'uiParameter') {
      // A single-file form: the controller splits the module and styles out of html.
      const parts = splitHtml(htmlToken.value, 'html');
      for (const message of parts.errors) report('error', message, htmlToken.start);
      if (parts.module !== null) {
        htmlModule = { text: parts.module, base: lineOf(src, htmlToken.contentStart) + lineOf(htmlToken.value, parts.moduleStart) - 1 };
        if ('module' in call.args) report('error', 'html has a <script type="module"> and the form has module too; keep one of them', htmlToken.start);
      }
    } else if (htmlToken) {
      checkHtml(htmlToken.value, (level, message) => report(level, message, htmlToken.start));
    }
    field('css');

    const formFrom = 'formFrom' in call.args && call.name === 'uiParameter';
    if (formFrom) {
      const inline = FORM_FROM_FIELDS.filter((key) => key in call.args);
      const fromToken = field('formFrom');
      if (inline.length) {
        report('error', `formFrom reads module, html, css, schema and defaultJson from '${fromToken?.value ?? 'the folder'}',`
          + ` so the Jenkinsfile must not set them too; remove ${inline.join(', ')}`, call.args.formFrom.start);
      }
      const pathError = fromToken && formFromPathError(fromToken.value);
      if (pathError) report('error', pathError, fromToken.start);
      else if (fromToken && root) await checkFolder(root, fromToken.value, runtime, (level, message) => report(level, message, fromToken.start));
    }

    const moduleToken = field('module');
    if (moduleToken) {
      const base = lineOf(src, moduleToken.contentStart);
      await checkModule(moduleToken.value, (level, message) => report(level, message, moduleToken.start),
        (level, line, column, message) => problems.push({ level, line: base + line - 1, column, message: `${label}: ${message}` }));
    } else if (htmlModule) {
      await checkModule(htmlModule.text, (level, message) => report(level, message, htmlToken.start),
        (level, line, column, message) => problems.push({ level, line: htmlModule.base + line - 1, column, message: `${label}: ${message}` }));
    } else if (!('module' in call.args) && !formFrom && runtime !== 'schema-form' && call.name === 'uiParameter') {
      report('error', `module is required unless runtime is 'schema-form'`);
    }
    if (runtime === 'schema-form' && call.name === 'uiParameter' && schema !== undefined && schema?.type !== 'object') {
      report('error', `runtime 'schema-form' needs a schema whose type is "object"`, schemaToken?.start);
    }
    if ('sandbox' in call.args) {
      const sandbox = call.args.sandbox;
      if (sandbox.type === 'str') {
        report('warning', `sandbox is the string '${sandbox.value}'; write true or false without quotes`, sandbox.start);
      } else if (sandbox.nonLiteral) {
        report('warning', 'sandbox is not a plain true or false, so it was not checked', sandbox.start);
      } else if (sandbox.value && !['module', 'html', 'css', 'formFrom'].some((key) => key in call.args)) {
        report('warning', 'sandbox: true has no effect: this form runs no code of its own (no module, html or css)', sandbox.start);
      }
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
    const full = path.resolve(base, file);
    try {
      src = readFileSync(full, 'utf8');
    } catch (e) {
      console.error(`${file}: cannot read: ${e.code ?? e.message}`);
      errors++;
      continue;
    }
    // formFrom paths are relative to the repository root.
    for (const p of await checkSource(src, { root: repoRoot(full) })) {
      if (p.level === 'error') errors++;
      if (p.message === NO_CALLS) empty++;
      console.log(`${file}:${p.line}:${p.column}: ${p.level}: ${p.message}`);
    }
  }
  if (empty) console.log(`${empty} of ${files.length} file(s) had nothing to check`);
  else if (errors === 0) console.log(`${files.length} file(s) checked, no errors`);
  process.exit(errors || empty ? 1 : 0);
}
