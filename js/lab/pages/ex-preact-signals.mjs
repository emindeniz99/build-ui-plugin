import { h, render } from 'preact';
import { signal, computed, effect } from '@preact/signals';
import htm from 'htm';
import { IMG, publish } from './common.mjs';
const html = htm.bind(h);
const branch = signal('main'), autoDeploy = signal(false), env = signal('dev');
const config = computed(() => ({ branch: branch.value, autoDeploy: autoDeploy.value, env: env.value }));
effect(() => publish(config.value));
const App = () => html`
  <img class="banner" src=${IMG} alt="logo" />
  <label>Branch <input id="branch" value=${branch}
    onInput=${e => (branch.value = e.currentTarget.value)} /></label>
  <label><input id="auto" type="checkbox" checked=${autoDeploy}
    onChange=${e => (autoDeploy.value = e.currentTarget.checked)} /> Auto deploy</label>
  ${autoDeploy.value && html`
    <label>Env <select id="env" value=${env}
      onChange=${e => (env.value = e.currentTarget.value)}>
      <option>dev</option><option>prod</option></select></label>`}`;
render(html`<${App} />`, document.getElementById('app'));
