import { createSignal, createStore, createEffect, onSettled, For, Show } from 'solid-js';
import { render } from '@solidjs/web';
import html from '@solidjs/html';
import { fetchServices, initial, publish, firstPaint, ready } from './cmp-shared.mjs';

const tpl = html.define({ For, Show });
let nextId = 1;

function Target(props) {
  return tpl`<fieldset><legend>Target</legend>
    <select id="region" value=${() => props.target.region} onChange=${(e) => props.onRegion(e.currentTarget.value)}>
      <option>eu</option><option>us</option></select>
    <input id="replicas" type="number" value=${() => props.target.replicas}
      onInput=${(e) => props.onReplicas(Number(e.currentTarget.value))} /></fieldset>`;
}

function App() {
  const [c, setC] = createStore(initial());
  const [options, setOptions] = createSignal([]);
  onSettled(() => {
    fetchServices().then(setOptions);
    const t = setInterval(() => window.__ticks++, 20);
    return () => clearInterval(t);
  });
  createEffect(() => JSON.stringify(c), (json) => { publish(JSON.parse(json)); firstPaint(); });
  return tpl`
    <input id="branch" value=${() => c.branch} onInput=${(e) => setC((d) => { d.branch = e.currentTarget.value; })} />
    <x-chips id="services" prop:options=${options} prop:value=${() => [...c.services]}
      onChips-change=${(e) => setC((d) => { d.services = e.detail; })} />
    <label><input id="deploy" type="checkbox" checked=${() => c.deploy}
      onChange=${(e) => setC((d) => { d.deploy = e.currentTarget.checked; })} /> Deploy</label>
    <Show when=${() => c.deploy}>
      <${Target} target=${c.target} onRegion=${(r) => setC((d) => { d.target.region = r; })}
        onReplicas=${(n) => setC((d) => { d.target.replicas = n; })} />
    </Show>
    <button id="add-step" type="button" onClick=${() => setC((d) => { d.steps.push({ id: nextId, name: `step ${nextId++}` }); })}>Add step</button>
    <ul><For each=${() => c.steps}>${(s) => tpl`<li>${() => s.name}
      <button class="remove" type="button" onClick=${(e) => setC((d) => { d.steps = d.steps.filter((x) => x.id !== s.id); })}>x</button></li>`}</For></ul>`;
}

const root = document.getElementById('app');
await ready();
window.__unmount = render(App, root);
