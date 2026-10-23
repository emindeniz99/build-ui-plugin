import { createApp } from 'petite-vue';
import { IMG, publish } from './common.mjs';
document.getElementById('app').innerHTML = `
  <div v-scope v-effect="publish({ branch, autoDeploy, env })">
    <img class="banner" :src="IMG" alt="logo">
    <label>Branch <input id="branch" v-model="branch"></label>
    <label><input id="auto" type="checkbox" v-model="autoDeploy"> Auto deploy</label>
    <label v-if="autoDeploy">Env
      <select id="env" v-model="env"><option>dev</option><option>prod</option></select></label>
  </div>`;
createApp({ branch: 'main', autoDeploy: false, env: 'dev', IMG, publish }).mount('#app');
