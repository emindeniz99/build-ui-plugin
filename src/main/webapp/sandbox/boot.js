// build-ui sandbox boot: runs one form's code inside <iframe sandbox="allow-scripts">.
// The frame has an opaque origin: no cookies, no Jenkins API, no access to the page.
// host.js sends everything the form needs in one message that carries a MessagePort;
// after that, all traffic goes over the port. Same mount() contract as on the page.

const { runtime: runtimeId, module: moduleURL } = document.documentElement.dataset;

const pageSheet = new CSSStyleSheet();
pageSheet.replaceSync('body { margin: 0; }');
document.adoptedStyleSheets = [pageSheet];

const baseSheet = new CSSStyleSheet();
// flow-root keeps the children's margins inside the host, so its height is the frame's height.
baseSheet.replaceSync(`
  :host { display: flow-root; color: var(--text-color, inherit); font: inherit; }
  .build-ui-error { color: color-mix(in oklch, var(--error-color, #c00) 65%, var(--text-color, #000)); white-space: pre-wrap; }
`);

addEventListener('message', function init(e) {
  if (e.source !== parent || e.data?.type !== 'build-ui:init' || !e.ports[0]) return;
  removeEventListener('message', init);
  start(e.data, e.ports[0]);
});

async function start(init, port) {
  const controller = new AbortController();
  const { signal } = controller;
  const waiting = new Map();
  let cleanup = null;
  let value = init.value;
  let next = 0;

  port.onmessage = ({ data }) => {
    if (data?.type === 'answer' && waiting.has(data.id)) {
      const { resolve, reject } = waiting.get(data.id);
      waiting.delete(data.id);
      if (data.ok) resolve(data.body); else reject(new Error(data.error));
    } else if (data?.type === 'unmount' && !signal.aborted) {
      controller.abort();
      if (typeof cleanup === 'function') cleanup();
    }
  };

  for (const [name, v] of Object.entries(init.theme || {})) document.documentElement.style.setProperty(name, v);

  const ui = {
    name: init.name,
    job: init.job,
    schema: init.schema,
    get value() { return value; },
    setValue(nextValue) {
      value = nextValue;
      port.postMessage({ type: 'setValue', json: JSON.stringify(nextValue) });
    },
    query(provider, args = {}, options = {}) {
      const s = options.signal || signal;
      return new Promise((resolve, reject) => {
        if (s.aborted) { reject(s.reason); return; }
        const id = ++next;
        waiting.set(id, { resolve, reject });
        s.addEventListener('abort', () => { if (waiting.delete(id)) reject(s.reason); }, { once: true });
        port.postMessage({ type: 'query', id, provider, args: JSON.parse(JSON.stringify(args)) });
      });
    },
  };

  try {
    const [runtime, job] = await Promise.all([
      runtimeId === 'none' ? {} : import(new URL(`runtimes/${runtimeId}.js`, import.meta.url).href),
      moduleURL ? import(moduleURL) : null,
    ]);
    const host = document.createElement('div');
    document.body.append(host);
    const shadow = host.attachShadow({ mode: 'open' });
    shadow.adoptedStyleSheets = [baseSheet];
    if (init.css) {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(init.css);
      shadow.adoptedStyleSheets = [baseSheet, sheet];
    }
    const root = document.createElement('div');
    root.className = 'build-ui-container';
    if (init.html) root.innerHTML = init.html;
    shadow.replaceChildren(root);
    new ResizeObserver(() => port.postMessage({ type: 'height', height: host.getBoundingClientRect().height })).observe(host);

    const mount = job ? job.default : runtime.mount;
    if (typeof mount !== 'function') {
      throw new Error(job ? 'the module has no default export function mount()' : `runtime ${runtimeId} needs a module`);
    }
    const result = await mount({ root, ui, runtime, signal });
    if (signal.aborted) {
      if (typeof result === 'function') result();
      return;
    }
    cleanup = result;
    port.postMessage({ type: 'mounted' });
  } catch (error) {
    port.postMessage({ type: 'error', message: error && error.message ? error.message : String(error) });
  }
}
