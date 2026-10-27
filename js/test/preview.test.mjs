// The preview is only worth having if a form that works there works on
// Jenkins and the other way round: same policy, same markup, same split, same
// verdict on a value. These tests pin each of those to the plugin's own files.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { CSP, answerQuery, startPreview, valueErrors } from '../preview/server.mjs';
import { validate } from '../preview/schema.mjs';

const at = (p) => fileURLToPath(new URL(p, import.meta.url));
const examples = at('../../examples/');
const quiet = { log() {}, error() {} };
const sha = (text) => crypto.createHash('sha256').update(text, 'utf8').digest('hex');

function tempDir(files) {
  const dir = mkdtempSync(path.join(tmpdir(), 'build-ui-preview-'));
  for (const [name, text] of Object.entries(files)) writeFileSync(path.join(dir, name), text);
  return dir;
}

async function withPreview(options, fn) {
  const preview = await startPreview({ port: 0, log: quiet, ...options });
  try {
    return await fn(preview);
  } finally {
    await preview.close();
  }
}

const attr = (pageText, name) => {
  const m = new RegExp(`${name}="([^"]*)"`).exec(pageText);
  return m && m[1].replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
};

test('the page carries the CSP header Jenkins sends, so a form that passes here passes there', async () => {
  // docs/design.md section 6 records the header measured on Jenkins 2.541.1 and 2.580.1.
  const design = readFileSync(at('../../docs/design.md'), 'utf8');
  const measured = /enforces this policy on\s+its pages[^`]*```\n([\s\S]*?)```/.exec(design)[1]
    .replace(/\s+/g, ' ').replace(/ ?report-to …; report-uri …/, '').trim().replace(/;$/, '');
  assert.ok(CSP.startsWith(measured), `${CSP}\ndoes not start with\n${measured}`);
  await withPreview({ target: path.join(examples, 'reference.Jenkinsfile') }, async ({ url }) => {
    const res = await fetch(url);
    assert.equal(res.headers.get('content-security-policy'), CSP);
    assert.equal(res.headers.get('content-security-policy-report-only'), null, 'enforced, not report-only');
  });
});

test('the form gets index.jelly markup and the shipped host.js, runtime and content-hash URLs', async () => {
  await withPreview({ target: path.join(examples, 'reference.Jenkinsfile') }, async ({ url }) => {
    const text = await (await fetch(url)).text();
    assert.match(text, /<div name="parameter" class="build-ui">/);
    assert.match(text, /<textarea name="value" class="jenkins-input build-ui-value"/);
    assert.equal(attr(text, 'data-name'), 'BUILD_CONFIG');
    assert.equal(attr(text, 'data-runtime'), 'preact11-htm3');
    assert.equal(attr(text, 'data-eval'), 'false');
    assert.equal(attr(text, 'data-sandbox'), 'false');
    assert.equal(JSON.parse(attr(text, 'data-schema')).required.join(), 'branch,services');

    const moduleKey = attr(text, 'data-module');
    const moduleRes = await fetch(new URL(`build-ui/${moduleKey}`, url));
    assert.equal(moduleRes.headers.get('content-type'), 'text/javascript;charset=UTF-8', 'a module with another MIME type does not load');
    assert.equal(moduleKey, `${sha(await moduleRes.text())}.mjs`, 'keys are the SHA-256 of the text, as in ContentStore');

    const src = /<script type="module" src="([^"]+host\.js)">/.exec(text)[1];
    const shipped = (p) => readFileSync(at(`../../src/main/webapp/${p}`), 'utf8');
    assert.equal(await (await fetch(new URL(src, url))).text(), shipped('host.js'));
    const runtime = await fetch(new URL('static/preview/plugin/build-ui/runtimes/preact11-htm3.js', url));
    assert.equal(await runtime.text(), shipped('runtimes/preact11-htm3.js'));
    assert.equal((await fetch(new URL('static/preview/plugin/build-ui/../../../../package.json', url))).status, 404);
  });
});

test('a single-file html is split as the controller splits it, and its refusals show as the form error', async () => {
  const dir = tempDir({
    Jenkinsfile: `uiParameter(name: 'ONE', css: '.a { color: red; }', html: '''
<style>.b { color: blue; }</style>
<p class="b">hi</p>
<script type="module">
export default function mount({ root }) { root.querySelector('.b').textContent = 'mounted' }
</script>''')
uiParameter(name: 'BAD', html: '<p>x</p><script>alert(1)</script>')
`,
  });
  try {
    await withPreview({ target: path.join(dir, 'Jenkinsfile') }, async ({ url }) => {
      const text = await (await fetch(url)).text();
      const [one, bad] = text.split('<div class="preview-row">').slice(1);
      const content = async (key) => (await fetch(new URL(`build-ui/${attr(one, key)}`, url))).text();
      assert.equal((await content('data-module')).trim(), "export default function mount({ root }) { root.querySelector('.b').textContent = 'mounted' }");
      assert.equal(await content('data-css'), '.a { color: red; }\n.b { color: blue; }', 'css first, then each <style>');
      const html = await content('data-html');
      assert.match(html, /<p class="b">hi<\/p>/);
      assert.doesNotMatch(html, /<script|<style/);
      // The controller refuses a classic script; Jenkins then shows the reason and the JSON field, without host.js.
      assert.match(bad, /This form could not be read, so enter the value as JSON\. html has a &lt;script&gt; without type=&quot;module&quot;/);
      assert.doesNotMatch(bad, /host\.js|build-ui-root/);
    });
  } finally {
    rmSync(dir, { recursive: true });
  }
});

test('ui.query is answered from the data file, by provider and optionally by args', async () => {
  const data = path.join(examples, 'preview-data.json');
  await withPreview({ target: path.join(examples, 'parallel-envs.Jenkinsfile'), data }, async ({ url }) => {
    const query = async (id, args) => {
      const res = await fetch(new URL(`build-ui/query/${id}?args=${encodeURIComponent(JSON.stringify(args))}`, url));
      return { status: res.status, body: await res.json() };
    };
    const withConfig = await query('jenkinsRuns', { job: 'preview', limit: 50, parameter: 'DEPLOY' });
    assert.equal(withConfig.status, 200);
    assert.deepEqual(withConfig.body[0].config.envs, ['env-1', 'env-3'], 'the case whose args match');
    const plain = await query('jenkinsRuns', { job: 'preview', limit: 3 });
    assert.equal(plain.body[0].number, 7, 'the case without args matches the rest');
    assert.equal((await query('gitRefs', { job: 'preview' })).body.tags[0].name, 'v1.4.0');

    // host.js turns { error } into the rejection the module sees, so the message must name the fix.
    const missing = await query('argoCdApp', { app: 'web' });
    assert.equal(missing.status, 404);
    assert.equal(missing.body.error, `preview: preview-data.json has no entry for data provider 'argoCdApp' (args {"app":"web"}); add "argoCdApp": <answer>`);
  });
  assert.match(answerQuery(null, 'gitRefs', {}).body.error, /no data file, so data provider 'gitRefs' has no answer; start the preview with --data/);
  const cases = { gitRefs: { $cases: [{ args: { tags: 'v*' }, answer: 1 }] } };
  assert.match(answerQuery(cases, 'gitRefs', { tags: '*' }, 'f.json').body.error, /no case of 'gitRefs' in f\.json matches args \{"tags":"\*"\}/);
});

test('the value verdict uses the controller\'s schema subset and messages', () => {
  const schema = {
    type: 'object', required: ['branch', 'services'], additionalProperties: false,
    properties: {
      branch: { type: 'string', minLength: 1, pattern: '^[a-z/]+$' },
      services: { type: 'array', minItems: 1, uniqueItems: true, items: { enum: ['api', 'worker'] } },
      replicas: { type: 'integer', minimum: 1, exclusiveMaximum: 10 },
      'a/b~c': { const: 5 },
    },
  };
  assert.deepEqual(validate(schema, { branch: 'main', services: ['api'], replicas: 3.0, 'a/b~c': 5.0 }), [], '3.0 is an integer, 5.0 equals 5');
  assert.deepEqual(validate(schema, { branch: 'Main', services: [], extra: 1 }), [
    '/branch: must match ^[a-z/]+$',
    '/services: must have at least 1 items',
    '/extra: no value is allowed here',
  ]);
  assert.deepEqual(validate(schema, { branch: '', services: ['api', 'api', 'web'], replicas: 10, 'a/b~c': 6 }), [
    '/branch: must have at least 1 characters',
    '/branch: must match ^[a-z/]+$',
    '/services: has duplicate item "api"',
    '/services/2: must be one of ["api","worker"]',
    '/replicas: must be < 10',
    '/a~1b~0c: must be 5',
  ]);
  assert.deepEqual(validate(schema, { services: ['api'] }), ["/: is missing required property 'branch'"]);
  assert.deepEqual(validate(schema, []), ['/: expected object, got array'], 'a type mismatch stops further checks, as in Schema.java');
  assert.deepEqual(validate({ type: ['string', 'null'] }, 1), ['/: expected one of ["string","null"], got number']);
  assert.deepEqual(validate({ uniqueItems: true }, [{ a: 1, b: 2 }, { b: 2, a: 1 }]), ['/: has duplicate item {"b":2,"a":1}'], 'key order does not matter');

  // Before the schema, the controller's JSON rules (Json.parse): empty, invalid, duplicate keys.
  const param = { name: 'CFG', schema, error: null };
  assert.deepEqual(valueErrors(param, '  '), ['value of CFG is empty']);
  assert.match(valueErrors(param, '{"branch": ')[0], /^value of CFG is not valid JSON: /);
  assert.deepEqual(valueErrors(param, '{"branch":"a","branch":"b","services":["api"]}'), ['value of CFG has the key "branch" twice in one object']);
  assert.deepEqual(valueErrors({ ...param, schema: null }, '{"anything": true}'), [], 'without a schema the controller checks only that the value is JSON');
});

test('a formFrom folder renders on its own or through its Jenkinsfile, with --root naming the repository', async () => {
  const folder = path.join(examples, 'form-from/forms/deploy');
  const html = readFileSync(path.join(folder, 'form.html'), 'utf8');
  await withPreview({ target: folder }, async ({ url }) => {
    const text = await (await fetch(url)).text();
    assert.equal(attr(text, 'data-name'), 'deploy');
    assert.equal(attr(text, 'data-runtime'), 'preact11-htm3');
    assert.match(text, /spellcheck="false">\{ &quot;env&quot;: &quot;dev&quot;, &quot;dryRun&quot;: true \}<\/textarea>/, 'default.json fills the textarea');
    const module = await (await fetch(new URL(`build-ui/${attr(text, 'data-module')}`, url))).text();
    assert.ok(html.includes(module), 'the module is the script of form.html');
  });
  const jenkinsfile = path.join(examples, 'form-from/Jenkinsfile');
  await withPreview({ target: jenkinsfile, root: path.join(examples, 'form-from') }, async ({ url }) => {
    assert.equal(attr(await (await fetch(url)).text(), 'data-name'), 'DEPLOY');
  });
  // Without --root the Git top level is the playground, which has no forms/deploy.
  await withPreview({ target: jenkinsfile }, async ({ url }) => {
    assert.match(await (await fetch(url)).text(), /formFrom folder 'forms\/deploy' is not under .*; pass --root with the repository root/);
  });
});

test('a sandboxed form gets the frame document under CSP and a module URL that works once', async () => {
  const dir = tempDir({ Jenkinsfile: "uiParameter(name: 'S', sandbox: true, module: 'export default function mount({ root }) { root.textContent = 1 }')\n" });
  try {
    await withPreview({ target: path.join(dir, 'Jenkinsfile') }, async ({ url }) => {
      const text = await (await fetch(url)).text();
      assert.equal(attr(text, 'data-sandbox'), 'true');
      const frame = await fetch(new URL(`build-ui/frame?runtime=preact11-htm3&module=${attr(text, 'data-module')}`, url));
      assert.equal(frame.headers.get('content-security-policy'), CSP);
      const moduleURL = attr(await frame.text(), 'data-module');
      const first = await fetch(new URL(moduleURL, url));
      assert.equal(first.headers.get('access-control-allow-origin'), '*', 'the frame has an opaque origin');
      assert.match(await first.text(), /root\.textContent = 1/);
      assert.equal((await fetch(new URL(moduleURL, url))).status, 404, 'tokens are single use, as in SandboxTokens');
      assert.equal((await fetch(new URL('build-ui/frame?runtime=../x', url))).status, 404);
    });
  } finally {
    rmSync(dir, { recursive: true });
  }
});

test('the preview listens on loopback only and refuses requests for other host names', async () => {
  await assert.rejects(startPreview({ target: path.join(examples, 'reference.Jenkinsfile'), host: '0.0.0.0', port: 0, log: quiet }),
    /listens on loopback only \(127\.0\.0\.1 or ::1\); refusing 0\.0\.0\.0/);
  await assert.rejects(startPreview({ target: path.join(examples, 'reference.Jenkinsfile'), host: '192.168.1.5', port: 0, log: quiet }), /refusing 192\.168\.1\.5/);
  await withPreview({ target: path.join(examples, 'reference.Jenkinsfile') }, async ({ url, server }) => {
    assert.equal(server.address().address, '127.0.0.1');
    assert.equal((await fetch(url)).status, 200);
    // A page on another site that rebinds its name to 127.0.0.1 sends its own Host header.
    // fetch() does not let a caller set Host, so this uses node:http.
    const { request } = await import('node:http');
    const status = await new Promise((resolve, reject) => {
      request(url, { headers: { Host: `evil.example:${server.address().port}` } }, (res) => { res.resume(); resolve(res.statusCode); })
        .on('error', reject).end();
    });
    assert.equal(status, 403);
  });
});

test('a change to the Jenkinsfile, a formFrom file or the data file tells open pages to reload', async () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'build-ui-preview-'));
  cpSync(path.join(examples, 'form-from'), dir, { recursive: true });
  writeFileSync(path.join(dir, 'data.json'), '{}');
  try {
    await withPreview({ target: path.join(dir, 'Jenkinsfile'), root: dir, data: path.join(dir, 'data.json') }, async ({ url }) => {
      await fetch(url); // the page load registers the formFrom folder
      const events = await fetch(new URL('_preview/events', url));
      const reader = events.body.getReader();
      const next = async () => {
        const deadline = setTimeout(() => reader.cancel(), 5000);
        let text = '';
        while (!text.includes('data: reload')) {
          const { value, done } = await reader.read();
          if (done) break;
          text += Buffer.from(value).toString();
        }
        clearTimeout(deadline);
        return text;
      };
      await reader.read(); // ": connected"
      for (const file of ['forms/deploy/form.html', 'Jenkinsfile', 'data.json']) {
        writeFileSync(path.join(dir, file), `${readFileSync(path.join(dir, file), 'utf8')}\n`);
        assert.match(await next(), /data: reload/, `no reload after ${file} changed`);
      }
    });
  } finally {
    rmSync(dir, { recursive: true });
  }
});

test('the CLI refuses a missing target and a parameter that does not exist, with the reason', async () => {
  const { spawnSync } = await import('node:child_process');
  const cli = (...args) => spawnSync(process.execPath, [at('../preview/server.mjs'), ...args], { encoding: 'utf8', env: { ...process.env, INIT_CWD: examples } });
  const none = cli();
  assert.equal(none.status, 2);
  assert.match(none.stderr, /usage: preview <Jenkinsfile \| formFrom folder>/);
  const wrong = cli('reference.Jenkinsfile', '--param', 'NOPE', '--port', '0');
  assert.equal(wrong.status, 1);
  assert.match(wrong.stderr, /no uiParameter named 'NOPE' in .*reference\.Jenkinsfile/);
});
