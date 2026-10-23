import { LitElement, html } from 'lit';
import { IMG, publish, initial } from './common.mjs';
class BuildForm extends LitElement {
  static properties = { c: { state: true } };
  constructor() { super(); this.c = initial(); } // not a class field: that would hide Lit's accessor
  set(patch) { this.c = { ...this.c, ...patch }; }
  updated() { publish(this.c); }
  render() {
    const c = this.c;
    return html`
      <img class="banner" src=${IMG} alt="logo" />
      <label>Branch <input id="branch" .value=${c.branch}
        @input=${e => this.set({ branch: e.target.value })} /></label>
      <label><input id="auto" type="checkbox" .checked=${c.autoDeploy}
        @change=${e => this.set({ autoDeploy: e.target.checked })} /> Auto deploy</label>
      ${c.autoDeploy ? html`
        <label>Env <select id="env" .value=${c.env}
          @change=${e => this.set({ env: e.target.value })}>
          <option>dev</option><option>prod</option></select></label>` : ''}`;
  }
}
customElements.define('build-form-lit', BuildForm); // global name, Shadow DOM by default
document.getElementById('app').innerHTML = '<build-form-lit></build-form-lit>';
