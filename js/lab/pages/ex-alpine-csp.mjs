import Alpine from '@alpinejs/csp';
import { IMG, publish } from './common.mjs';
// CSP build: no inline JS expressions; logic lives in a registered component.
Alpine.data('buildForm', () => ({
  branch: 'main', autoDeploy: false, env: 'dev', img: IMG,
  init() { this.$watch('branch', () => this.out()); this.$watch('autoDeploy', () => this.out());
           this.$watch('env', () => this.out()); this.out(); },
  out() { publish({ branch: this.branch, autoDeploy: this.autoDeploy, env: this.env }); },
}));
document.getElementById('app').innerHTML = `
  <div x-data="buildForm">
    <img class="banner" :src="img" alt="logo">
    <label>Branch <input id="branch" x-model="branch"></label>
    <label><input id="auto" type="checkbox" x-model="autoDeploy"> Auto deploy</label>
    <label x-show="autoDeploy">Env
      <select id="env" x-model="env"><option>dev</option><option>prod</option></select></label>
  </div>`;
Alpine.start();
