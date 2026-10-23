// Shared pieces of the Preact vs Solid 2 comparison (design.md section 7).

// A deliberately plain custom element, like many hand-written ones: array
// properties `options` and `value`, a `chips-change` event, and no handling of
// properties set before it was defined (unless the page URL has ?safe=1).
export class XChips extends HTMLElement {
  #options = [];
  #value = [];
  constructor() {
    super();
    // ?safe=1: re-apply properties set before the upgrade, as Lit-based elements do.
    if (new URLSearchParams(location.search).has('safe')) {
      for (const p of ['options', 'value']) {
        if (Object.prototype.hasOwnProperty.call(this, p)) { const v = this[p]; delete this[p]; this[p] = v; }
      }
    }
  }
  set options(v) { this.#options = [...v]; this.render(); }
  get options() { return this.#options; }
  set value(v) { this.#value = [...v]; this.render(); }
  get value() { return this.#value; }
  connectedCallback() { this.render(); }
  render() {
    this.replaceChildren(...this.#options.map((option) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = option;
      b.dataset.option = option;
      b.setAttribute('aria-pressed', String(this.#value.includes(option)));
      b.addEventListener('click', () => {
        const next = this.#value.includes(option) ? this.#value.filter((o) => o !== option) : [...this.#value, option];
        this.dispatchEvent(new CustomEvent('chips-change', { detail: next, bubbles: true }));
      });
      return b;
    }));
  }
}

/** Defines <x-chips> 300 ms after page load, after the first render. */
export const defined = new Promise((resolve) => setTimeout(() => {
  customElements.define('x-chips', XChips);
  resolve();
}, 300));

/** Server data, 100 ms late. */
export const fetchServices = () => new Promise((resolve) => setTimeout(() => resolve(['api', 'worker', 'web']), 100));

export const initial = () => ({ branch: 'main', services: ['api'], deploy: false, target: { region: 'eu', replicas: 1 }, steps: [] });

export const publish = (v) => { document.getElementById('out').textContent = JSON.stringify(v); };

export const started = performance.now();
export function firstPaint() {
  if (window.__firstPaint === undefined) window.__firstPaint = performance.now() - started;
}

/** Waits for the element only when the page URL asks for it (?wait=1). */
export const ready = () => (new URLSearchParams(location.search).has('wait') ? defined : Promise.resolve());

window.__ticks = 0;
