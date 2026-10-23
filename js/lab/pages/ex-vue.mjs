import { createApp } from 'vue';
import { IMG, publish, initial } from './common.mjs';
createApp({
  data: () => ({ ...initial(), IMG }),
  watch: { $data: { handler(d) { publish({ branch: d.branch, autoDeploy: d.autoDeploy, env: d.env }); },
                    deep: true, immediate: true } },
  template: `
    <img class="banner" :src="IMG" alt="logo">
    <label>Branch <input id="branch" v-model="branch"></label>
    <label><input id="auto" type="checkbox" v-model="autoDeploy"> Auto deploy</label>
    <label v-if="autoDeploy">Env
      <select id="env" v-model="env"><option>dev</option><option>prod</option></select></label>`,
}).mount('#app');
