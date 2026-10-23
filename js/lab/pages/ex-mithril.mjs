import 'mithril/mithril.min.js'; // UMD build: sets window.m
import { IMG, publish, initial } from './common.mjs';
const m = window.m;
const c = initial();
const App = {
  onupdate: () => publish(c), oncreate: () => publish(c),
  view: () => [
    m('img.banner', { src: IMG, alt: 'logo' }),
    m('label', 'Branch ', m('input#branch', { value: c.branch, oninput: e => (c.branch = e.target.value) })),
    m('label', m('input#auto[type=checkbox]', { checked: c.autoDeploy,
      onchange: e => (c.autoDeploy = e.target.checked) }), ' Auto deploy'),
    c.autoDeploy && m('label', 'Env ', m('select#env', { value: c.env,
      onchange: e => (c.env = e.target.value) }, m('option', 'dev'), m('option', 'prod'))),
  ],
};
m.mount(document.getElementById('app'), App);
