import { html, render, signal, effect } from 'uhtml';
import { IMG, publish } from './common.mjs';
const branch = signal('main'), autoDeploy = signal(false), env = signal('dev');
effect(() => publish({ branch: branch.value, autoDeploy: autoDeploy.value, env: env.value }));
// Reactivity is per component; swapping a conditional template crashed in 5.0.9, so toggle ?hidden.
const App = () => html`
  <img class="banner" src=${IMG} alt="logo" />
  <label>Branch <input id="branch" .value=${branch.value}
    @input=${e => (branch.value = e.target.value)} /></label>
  <label><input id="auto" type="checkbox" .checked=${autoDeploy.value}
    @change=${e => (autoDeploy.value = e.target.checked)} /> Auto deploy</label>
  <label ?hidden=${!autoDeploy.value}>Env <select id="env" .value=${env.value}
    @change=${e => (env.value = e.target.value)}>
    <option>dev</option><option>prod</option></select></label>`;
render(document.getElementById('app'), () => html`<${App} />`);
