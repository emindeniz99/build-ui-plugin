import { h, render } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import htm from 'htm';
import { fetchServices, initial, publish, firstPaint, ready } from './cmp-shared.mjs';

const html = htm.bind(h);
let nextId = 1;

function Target({ target, onChange }) {
  return html`<fieldset><legend>Target</legend>
    <select id="region" value=${target.region} onChange=${(e) => onChange({ ...target, region: e.currentTarget.value })}>
      <option>eu</option><option>us</option></select>
    <input id="replicas" type="number" value=${target.replicas}
      onInput=${(e) => onChange({ ...target, replicas: Number(e.currentTarget.value) })} /></fieldset>`;
}

function App() {
  const [c, setC] = useState(initial);
  const [options, setOptions] = useState([]);
  useEffect(() => { fetchServices().then(setOptions); }, []);
  useEffect(() => { const t = setInterval(() => window.__ticks++, 20); return () => clearInterval(t); }, []);
  useEffect(() => { publish(c); firstPaint(); }, [c]);
  const set = (patch) => setC((old) => ({ ...old, ...patch }));
  return html`
    <input id="branch" value=${c.branch} onInput=${(e) => set({ branch: e.currentTarget.value })} />
    <x-chips id="services" options=${options} value=${c.services} onchips-change=${(e) => set({ services: e.detail })} />
    <label><input id="deploy" type="checkbox" checked=${c.deploy} onChange=${(e) => set({ deploy: e.currentTarget.checked })} /> Deploy</label>
    ${c.deploy && html`<${Target} target=${c.target} onChange=${(target) => set({ target })} />`}
    <button id="add-step" type="button" onClick=${() => set({ steps: [...c.steps, { id: nextId, name: `step ${nextId++}` }] })}>Add step</button>
    <ul>${c.steps.map((s) => html`<li key=${s.id}>${s.name}
      <button class="remove" type="button" onClick=${() => set({ steps: c.steps.filter((x) => x.id !== s.id) })}>x</button></li>`)}</ul>`;
}

const root = document.getElementById('app');
await ready();
render(html`<${App} />`, root);
window.__unmount = () => render(null, root);
