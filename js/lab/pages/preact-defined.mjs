// Fix probe: wait for definitions, then render.
await import('./wa.mjs');
await customElements.whenDefined('wa-select');
const { h, render } = await import('preact'); const htm = (await import('htm')).default; const html = htm.bind(h);
render(html`<wa-select id="services" multiple value=${['api', 'worker']}>
  <wa-option value="api">api</wa-option><wa-option value="worker">worker</wa-option></wa-select>`, document.getElementById('app'));
await new Promise(r => setTimeout(r, 300));
const el = document.getElementById('services');
document.getElementById('out').textContent = JSON.stringify({ value: el.value, attr: el.getAttribute('value') });
