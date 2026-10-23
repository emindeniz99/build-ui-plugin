import { html, reactive, watch } from '@arrow-js/core';
import { IMG, publish, initial } from './common.mjs';
const c = reactive(initial());
watch(() => publish({ branch: c.branch, autoDeploy: c.autoDeploy, env: c.env }));
html`
  <img class="banner" src="${IMG}" alt="logo" />
  <label>Branch <input id="branch" value="${() => c.branch}"
    @input="${e => (c.branch = e.target.value)}" /></label>
  <label><input id="auto" type="checkbox"
    @change="${e => (c.autoDeploy = e.target.checked)}" /> Auto deploy</label>
  ${() => c.autoDeploy ? html`
    <label>Env <select id="env" @change="${e => (c.env = e.target.value)}">
      <option>dev</option><option>prod</option></select></label>` : ''}`(document.getElementById('app'));
