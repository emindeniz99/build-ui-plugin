import { IMG, publish, initial } from './common.mjs';
const app = document.getElementById('app');
app.innerHTML = `
  <img class="banner" alt="logo">
  <label>Branch <input id="branch"></label>
  <label><input id="auto" type="checkbox"> Auto deploy</label>
  <label id="envRow" hidden>Env
    <select id="env"><option>dev</option><option>prod</option></select></label>`;
const $ = sel => app.querySelector(sel);
const state = initial();
$('.banner').src = IMG;
$('#branch').value = state.branch;
function update() { $('#envRow').hidden = !state.autoDeploy; publish(state); }
$('#branch').addEventListener('input', e => { state.branch = e.target.value; update(); });
$('#auto').addEventListener('change', e => { state.autoDeploy = e.target.checked; update(); });
$('#env').addEventListener('change', e => { state.env = e.target.value; update(); });
update();
