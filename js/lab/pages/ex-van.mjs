import van from 'vanjs-core';
import { IMG, publish } from './common.mjs';
const { img, label, input, select, option } = van.tags;
const branch = van.state('main'), autoDeploy = van.state(false), env = van.state('dev');
van.derive(() => publish({ branch: branch.val, autoDeploy: autoDeploy.val, env: env.val }));
van.add(document.getElementById('app'),
  img({ class: 'banner', src: IMG, alt: 'logo' }),
  label('Branch ', input({ id: 'branch', value: branch, oninput: e => (branch.val = e.target.value) })),
  label(input({ id: 'auto', type: 'checkbox', checked: autoDeploy,
    onchange: e => (autoDeploy.val = e.target.checked) }), ' Auto deploy'),
  () => autoDeploy.val
    ? label('Env ', select({ id: 'env', value: env, onchange: e => (env.val = e.target.value) },
        option('dev'), option('prod')))
    : '');
