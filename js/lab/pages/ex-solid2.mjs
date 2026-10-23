import { createSignal, createEffect } from 'solid-js';
import { render } from '@solidjs/web';
import html from '@solidjs/html';
import { IMG, publish } from './common.mjs';
function App() {
  const [branch, setBranch] = createSignal('main');
  const [autoDeploy, setAuto] = createSignal(false);
  const [env, setEnv] = createSignal('dev');
  // Solid 2.0 splits effects: track first, then run the side effect.
  createEffect(() => ({ branch: branch(), autoDeploy: autoDeploy(), env: env() }), publish);
  return html`
    <img class="banner" src=${IMG} alt="logo" />
    <label>Branch <input id="branch" value=${branch} onInput=${e => setBranch(e.target.value)} /></label>
    <label><input id="auto" type="checkbox" checked=${autoDeploy}
      onChange=${e => setAuto(e.target.checked)} /> Auto deploy</label>
    ${() => autoDeploy() && html`
      <label>Env <select id="env" value=${env} onChange=${e => setEnv(e.target.value)}>
        <option>dev</option><option>prod</option></select></label>`}`;
}
render(App, document.getElementById('app'));
