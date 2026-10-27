// The preview's own page script, outside every form: it shows each parameter's
// value and the controller's verdict on it, lists CSP violations, and reloads
// the page when the server reports a changed file. host.js writes the value
// into the textarea without an event, so this polls the textarea.

const cspList = document.querySelector('.preview-csp ul');
document.addEventListener('securitypolicyviolation', (e) => {
  const item = document.createElement('li');
  item.textContent = `${e.effectiveDirective} blocked ${e.blockedURI || 'inline code'}${e.sourceFile ? ` in ${e.sourceFile}:${e.lineNumber}` : ''}`;
  cspList.append(item);
  cspList.parentElement.hidden = false;
});

function follow(form) {
  const textarea = form.querySelector('textarea.build-ui-value');
  const name = form.querySelector('input[name="name"]').value;
  const panel = document.querySelector(`.preview-value[data-for="${CSS.escape(name)}"]`);
  const pre = panel.querySelector('.preview-json');
  const verdict = panel.querySelector('.preview-verdict');
  const list = panel.querySelector('.preview-errors');
  let shown = null;
  let asked = 0;
  async function update() {
    const text = textarea.value;
    if (text === shown) return;
    shown = text;
    pre.textContent = text;
    const ticket = ++asked;
    const res = await fetch('/_preview/validate', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, value: text }),
    });
    const { errors = [], error } = await res.json();
    if (ticket !== asked) return; // a newer value is on its way
    const all = error ? [error] : errors;
    panel.dataset.valid = String(all.length === 0);
    verdict.textContent = all.length === 0 ? 'The controller would accept this value.' : 'The controller would refuse this value:';
    list.replaceChildren(...all.map((message) => Object.assign(document.createElement('li'), { textContent: message })));
  }
  textarea.addEventListener('input', update);
  setInterval(update, 200);
  update();
}

for (const form of document.querySelectorAll('.build-ui')) follow(form);

new EventSource('/_preview/events').addEventListener('message', (e) => {
  if (e.data === 'reload') location.reload();
});
