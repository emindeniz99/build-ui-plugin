import { h, render } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import htm from 'htm';
import { IMG, publish } from './wa.mjs';
const html = htm.bind(h);
function App() {
  const [c, set] = useState({ branch: 'main', autoDeploy: false, services: ['api', 'worker'], env: 'dev' });
  useEffect(() => publish(c), [c]);
  return html`<div class="grid">
    <img class="banner" src=${IMG} alt="logo" />
    <wa-input id="branch" label="Branch" value=${c.branch} onInput=${e => set({ ...c, branch: e.currentTarget.value })}></wa-input>
    <wa-select id="services" label="Services" multiple value=${c.services} onChange=${e => set({ ...c, services: e.currentTarget.value })}>
      <wa-option value="api">api</wa-option><wa-option value="worker">worker</wa-option><wa-option value="web">web</wa-option>
    </wa-select>
    <wa-checkbox id="auto" checked=${c.autoDeploy} onChange=${e => set({ ...c, autoDeploy: e.currentTarget.checked })}>Auto deploy</wa-checkbox>
    ${c.autoDeploy && html`<wa-select id="env" label="Env" value=${c.env} onChange=${e => set({ ...c, env: e.currentTarget.value })}>
      <wa-option value="dev">dev</wa-option><wa-option value="prod">prod</wa-option></wa-select>`}
  </div>`;
}
render(html`<${App} />`, document.getElementById('app'));
