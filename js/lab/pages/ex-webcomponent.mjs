import { IMG, publish, initial } from './common.mjs';
class BuildForm extends HTMLElement {
  state = initial();
  connectedCallback() {
    this.innerHTML = `
      <img class="banner" alt="logo" src="${IMG}">
      <label>Branch <input id="branch"></label>
      <label><input id="auto" type="checkbox"> Auto deploy</label>
      <label id="envRow" hidden>Env
        <select id="env"><option>dev</option><option>prod</option></select></label>`;
    this.querySelector('#branch').value = this.state.branch;
    this.addEventListener('input', () => this.read());
    this.addEventListener('change', () => this.read());
    this.read();
  }
  read() {
    const q = s => this.querySelector(s);
    Object.assign(this.state, { branch: q('#branch').value, autoDeploy: q('#auto').checked, env: q('#env').value });
    q('#envRow').hidden = !this.state.autoDeploy;
    publish(this.state);
  }
}
customElements.define('build-form', BuildForm); // global name: one per page
document.getElementById('app').innerHTML = '<build-form></build-form>';
