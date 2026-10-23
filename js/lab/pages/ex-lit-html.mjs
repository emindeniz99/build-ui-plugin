import { html, render } from 'lit-html';
import { IMG, publish, initial } from './common.mjs';
let c = initial();
const set = patch => { c = { ...c, ...patch }; draw(); };
const view = () => html`
  <img class="banner" src=${IMG} alt="logo" />
  <label>Branch <input id="branch" .value=${c.branch}
    @input=${e => set({ branch: e.target.value })} /></label>
  <label><input id="auto" type="checkbox" .checked=${c.autoDeploy}
    @change=${e => set({ autoDeploy: e.target.checked })} /> Auto deploy</label>
  ${c.autoDeploy ? html`
    <label>Env <select id="env" .value=${c.env}
      @change=${e => set({ env: e.target.value })}>
      <option>dev</option><option>prod</option></select></label>` : ''}`;
function draw() { render(view(), document.getElementById('app')); publish(c); }
draw();
