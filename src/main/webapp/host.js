// build-ui host: mounts each job module into its parameter on the page.
// Contract: export default async function mount({ root, ui, runtime, signal }) -> cleanup
// The textarea next to the root is what the form submits; ui.setValue writes it.

const head = document.head.dataset;
const rootURL = head.rooturl || '';
const resURL = head.resurl || rootURL;
const contentURL = `${rootURL}/build-ui/`;
const runtimeURL = (id) => `${resURL}/plugin/build-ui/runtimes/${id}.js`;

const baseSheet = new CSSStyleSheet();
baseSheet.replaceSync(`
  :host { display: block; color: var(--text-color, inherit); font: inherit; }
  /* Jenkins 2.541 --error-color is under 4.5:1 on its background; mixing in the text color keeps the hue and passes, in light and dark themes. */
  .build-ui-error { color: color-mix(in oklch, var(--error-color, #c00) 65%, var(--text-color, #000)); white-space: pre-wrap; }
`);

// Runtimes marked data-eval compile templates with eval. Enforced CSP blocks it,
// and Alpine then leaves the form blank with only console warnings, so check
// once per page before loading one. In report-only mode the check passes and
// the browser reports it.
let evalBlocked;
function blocksEval() {
  if (evalBlocked === undefined) {
    try { new Function(''); evalBlocked = false; } catch { evalBlocked = true; }
  }
  return evalBlocked;
}

const runtimes = new Map();
function loadRuntime(id) {
  if (id === 'none') return Promise.resolve({});
  // Plain names only: a path here would import any same-origin script.
  if (!/^[a-z0-9][a-z0-9-]*$/.test(id)) return Promise.reject(new Error(`unknown runtime '${id}'`));
  if (!runtimes.has(id)) runtimes.set(id, import(runtimeURL(id)));
  return runtimes.get(id);
}

async function fetchText(key, signal) {
  const res = await fetch(contentURL + key, { signal, credentials: 'same-origin' });
  if (!res.ok) throw new Error(`could not load ${key}: HTTP ${res.status}`);
  return res.text();
}

function parseJson(text, fallback) {
  try { return JSON.parse(text); } catch { return fallback; }
}

function makeUi(el, textarea, signal) {
  let value = parseJson(textarea.value, {});
  return {
    name: el.dataset.name,
    job: el.dataset.job || '',
    schema: el.dataset.schema ? parseJson(el.dataset.schema, null) : null,
    get value() { return value; },
    setValue(next) {
      value = next;
      textarea.value = JSON.stringify(next, null, 2);
    },
    async query(id, args = {}, options = {}) {
      const url = `${contentURL}query/${encodeURIComponent(id)}?args=${encodeURIComponent(JSON.stringify(args))}`;
      const res = await fetch(url, { signal: options.signal || signal, credentials: 'same-origin', headers: { Accept: 'application/json' } });
      // Jenkins answers some errors (403, 500, expired session) with HTML.
      const body = parseJson(await res.text(), undefined);
      if (!res.ok || body === undefined) throw new Error(body?.error || `query ${id} failed: HTTP ${res.status}`);
      return body;
    },
  };
}

// Jenkins's .jenkins-input sets display, which beats the hidden attribute, so set both.
function showTextarea(textarea, shown) {
  textarea.hidden = !shown;
  textarea.style.display = shown ? '' : 'none';
}

function showError(el, textarea, error) {
  showTextarea(textarea, true);
  const box = document.createElement('div');
  box.className = 'build-ui-error';
  box.setAttribute('role', 'alert');
  // A panel's textarea is read-only.
  const what = textarea.readOnly ? 'This panel could not load; its data is shown below.' : 'This form could not load, so edit the JSON value below instead.';
  box.textContent = `${what}\n${error && error.message ? error.message : error}`;
  el.shadowRoot.replaceChildren(box);
  console.error('[build-ui]', el.dataset.name, error);
}

async function mountOne(el) {
  const textarea = el.parentElement.querySelector('textarea.build-ui-value');
  const controller = new AbortController();
  const { signal } = controller;
  const shadow = el.attachShadow({ mode: 'open' });
  shadow.adoptedStyleSheets = [baseSheet];
  // Outside the shadow root a key event's target is this host, not the field, so
  // page shortcuts that skip typing in fields (Jenkins's "/" focuses search) would
  // fire while someone types "feature/x". Character keys typed in a field ("/",
  // Ctrl+K) stay in the form; Escape, arrows and Enter reach the module's listeners.
  el.addEventListener('keydown', (e) => {
    const t = e.composedPath()[0];
    if (e.key?.length === 1 && t instanceof HTMLElement && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) e.stopPropagation();
  });
  let cleanup = null;
  const unmount = () => {
    if (signal.aborted) return;
    controller.abort();
    if (typeof cleanup === 'function') cleanup();
  };
  el.buildUiUnmount = unmount;

  try {
    const { runtime: runtimeId, module: moduleKey, css: cssKey, html: htmlKey } = el.dataset;
    if (el.dataset.eval === 'true' && blocksEval()) {
      throw new Error(`This form's runtime ${runtimeId} needs eval, which this Jenkins's Content Security Policy blocks.`);
    }
    const [runtime, css, html, job] = await Promise.all([
      loadRuntime(runtimeId),
      cssKey ? fetchText(cssKey, signal) : null,
      htmlKey ? fetchText(htmlKey, signal) : null,
      moduleKey ? import(contentURL + moduleKey) : null,
    ]);
    // Removed while loading: mount would get a signal that has already fired.
    if (signal.aborted) return;
    if (css) {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      shadow.adoptedStyleSheets = [baseSheet, sheet];
    }
    const root = document.createElement('div');
    root.className = 'build-ui-container';
    if (html) root.innerHTML = html;
    shadow.replaceChildren(root);

    const mount = job ? job.default : runtime.mount;
    if (typeof mount !== 'function') {
      throw new Error(job ? 'the module has no default export function mount()' : `runtime ${runtimeId} needs a module`);
    }
    showTextarea(textarea, false);
    const result = await mount({ root, ui: makeUi(el, textarea, signal), runtime, signal });
    if (signal.aborted) {
      if (typeof result === 'function') result();
      return;
    }
    cleanup = result;
    el.dataset.mounted = 'true';
  } catch (error) {
    if (!signal.aborted) showError(el, textarea, error);
  }
}

function mountAll() {
  for (const el of document.querySelectorAll('.build-ui-root:not([data-started])')) {
    el.dataset.started = 'true';
    mountOne(el);
  }
}

// Unmount roots that leave the page (for example an input step that completes).
new MutationObserver((records) => {
  for (const record of records) {
    for (const node of record.removedNodes) {
      if (!(node instanceof Element)) continue;
      const roots = node.matches('.build-ui-root') ? [node] : node.querySelectorAll('.build-ui-root');
      for (const el of roots) el.buildUiUnmount?.();
    }
  }
  mountAll();
}).observe(document.body, { childList: true, subtree: true });

// A persisted page sits in the back/forward cache and returns as it was.
window.addEventListener('pagehide', (e) => {
  if (e.persisted) return;
  for (const el of document.querySelectorAll('.build-ui-root')) el.buildUiUnmount?.();
});

mountAll();
