import { h, render } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import htm from 'htm';
import { IMG, publish, initial } from './common.mjs';
const html = htm.bind(h);
function App() {
  const [c, set] = useState(initial);
  useEffect(() => publish(c), [c]);
  return html`
    <img class="banner" src=${IMG} alt="logo" />
    <label>Branch <input id="branch" value=${c.branch}
      onInput=${e => set({ ...c, branch: e.currentTarget.value })} /></label>
    <label><input id="auto" type="checkbox" checked=${c.autoDeploy}
      onChange=${e => set({ ...c, autoDeploy: e.currentTarget.checked })} /> Auto deploy</label>
    ${c.autoDeploy && html`
      <label>Env <select id="env" value=${c.env}
        onChange=${e => set({ ...c, env: e.currentTarget.value })}>
        <option>dev</option><option>prod</option></select></label>`}`;
}
render(html`<${App} />`, document.getElementById('app'));
