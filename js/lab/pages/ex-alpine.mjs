import Alpine from 'alpinejs';
import { IMG, publish } from './common.mjs';
window.IMG = IMG; window.publish = publish;
document.getElementById('app').innerHTML = `
  <div x-data="{ branch: 'main', autoDeploy: false, env: 'dev' }"
       x-effect="publish({ branch, autoDeploy, env })">
    <img class="banner" :src="IMG" alt="logo">
    <label>Branch <input id="branch" x-model="branch"></label>
    <label><input id="auto" type="checkbox" x-model="autoDeploy"> Auto deploy</label>
    <label x-show="autoDeploy">Env
      <select id="env" x-model="env"><option>dev</option><option>prod</option></select></label>
  </div>`;
Alpine.start();
