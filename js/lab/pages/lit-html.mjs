import { html, render } from 'lit-html';
import { IMG, publish } from './wa.mjs';
let c = { branch: 'main', autoDeploy: false, services: ['api', 'worker'], env: 'dev' };
const set = n => { c = n; draw(); };
const view = () => html`<div class="grid">
  <img class="banner" src=${IMG} alt="logo" />
  <wa-input id="branch" label="Branch" .value=${c.branch} @input=${e => set({ ...c, branch: e.currentTarget.value })}></wa-input>
  <wa-select id="services" label="Services" multiple .value=${c.services} @change=${e => set({ ...c, services: e.currentTarget.value })}>
    <wa-option value="api">api</wa-option><wa-option value="worker">worker</wa-option><wa-option value="web">web</wa-option>
  </wa-select>
  <wa-checkbox id="auto" .checked=${c.autoDeploy} @change=${e => set({ ...c, autoDeploy: e.currentTarget.checked })}>Auto deploy</wa-checkbox>
  ${c.autoDeploy ? html`<wa-select id="env" label="Env" .value=${c.env} @change=${e => set({ ...c, env: e.currentTarget.value })}>
    <wa-option value="dev">dev</wa-option><wa-option value="prod">prod</wa-option></wa-select>` : ''}
</div>`;
function draw() { render(view(), document.getElementById('app')); publish(c); }
draw();
