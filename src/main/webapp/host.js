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
  iframe { display: block; width: 100%; height: 0; border: 0; }
`);

const runtimes = new Map();
function loadRuntime(id) {
  if (id === 'none') return Promise.resolve({});
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
    query: (id, args = {}, options = {}) => query(id, args, options.signal || signal),
  };
}

async function query(id, args, signal) {
  const url = `${contentURL}query/${encodeURIComponent(id)}?args=${encodeURIComponent(JSON.stringify(args))}`;
  const res = await fetch(url, { signal, credentials: 'same-origin', headers: { Accept: 'application/json' } });
  const body = await res.json();
  if (!res.ok) throw new Error(body.error || `query ${id} failed: HTTP ${res.status}`);
  return body;
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
  box.textContent = `This form could not load, so edit the JSON value below instead.\n${error && error.message ? error.message : error}`;
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
  // fire while someone types "feature/x". Keys typed in a field stay in the form.
  el.addEventListener('keydown', (e) => {
    const t = e.composedPath()[0];
    if (t instanceof HTMLElement && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName))) e.stopPropagation();
  });
  let cleanup = null;
  const unmount = () => {
    if (signal.aborted) return;
    controller.abort();
    if (typeof cleanup === 'function') cleanup();
  };
  el.buildUiUnmount = unmount;

  try {
    if (el.dataset.sandbox === 'true') {
      await mountSandboxed(el, textarea, signal);
      el.dataset.mounted = 'true';
      return;
    }
    const { runtime: runtimeId, module: moduleKey, css: cssKey, html: htmlKey } = el.dataset;
    const [runtime, css, html, job] = await Promise.all([
      loadRuntime(runtimeId),
      cssKey ? fetchText(cssKey, signal) : null,
      htmlKey ? fetchText(htmlKey, signal) : null,
      moduleKey ? import(contentURL + moduleKey) : null,
    ]);
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

// A sandboxed form runs in <iframe sandbox="allow-scripts">: an opaque origin with
// no cookies, no Jenkins API and no access to this page. It gets its data in one
// message carrying a MessagePort, and only that port is listened to afterwards.
async function mountSandboxed(el, textarea, signal) {
  const { runtime, module, css: cssKey, html: htmlKey } = el.dataset;
  const [css, html] = await Promise.all([
    cssKey ? fetchText(cssKey, signal) : null,
    htmlKey ? fetchText(htmlKey, signal) : null,
  ]);
  const frame = document.createElement('iframe');
  frame.setAttribute('sandbox', 'allow-scripts');
  frame.title = el.dataset.name;
  frame.src = `${contentURL}frame?runtime=${encodeURIComponent(runtime)}${module ? `&module=${encodeURIComponent(module)}` : ''}`;
  const { port1: port, port2 } = new MessageChannel();
  signal.addEventListener('abort', () => { port.postMessage({ type: 'unmount' }); port.close(); });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('the sandboxed form did not start within 30 s')), 30_000);
    const done = (fn, arg) => { clearTimeout(timer); fn(arg); };
    port.onmessage = ({ data }) => {
      if (signal.aborted || !data) return;
      if (data.type === 'mounted') done(resolve);
      else if (data.type === 'error') done(reject, new Error(String(data.message)));
      else if (data.type === 'setValue' && typeof data.json === 'string') {
        try { textarea.value = JSON.stringify(JSON.parse(data.json), null, 2); } catch { /* not JSON: keep the last value */ }
      } else if (data.type === 'height' && Number.isFinite(data.height)) {
        frame.style.height = `${Math.min(Math.max(Math.ceil(data.height), 0), 10_000)}px`;
      } else if (data.type === 'query' && typeof data.provider === 'string') {
        query(data.provider, data.args ?? {}, signal).then(
          (body) => port.postMessage({ type: 'answer', id: data.id, ok: true, body }),
          (e) => port.postMessage({ type: 'answer', id: data.id, ok: false, error: e && e.message ? e.message : String(e) }));
      }
    };
    frame.addEventListener('load', () => {
      frame.contentWindow.postMessage({
        type: 'build-ui:init',
        name: el.dataset.name,
        job: el.dataset.job || '',
        schema: el.dataset.schema ? parseJson(el.dataset.schema, null) : null,
        value: parseJson(textarea.value, {}),
        html,
        css,
        theme: theme(el),
      }, '*', [port2]);
    }, { once: true });
    showTextarea(textarea, false);
    el.shadowRoot.replaceChildren(frame);
  });
}

// Jenkins's theme as computed here: its custom properties and the text style, so the frame matches the page.
function theme(el) {
  const style = getComputedStyle(el);
  const out = {};
  for (const name of style) if (name.startsWith('--')) out[name] = style.getPropertyValue(name);
  for (const name of ['color', 'font-family', 'font-size', 'line-height', 'color-scheme']) out[name] = style.getPropertyValue(name);
  return out;
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

window.addEventListener('pagehide', () => {
  for (const el of document.querySelectorAll('.build-ui-root')) el.buildUiUnmount?.();
});

mountAll();
