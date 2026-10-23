export const IMG = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48"><circle cx="24" cy="24" r="20" fill="teal"/></svg>');
export const publish = v => { document.getElementById('out').textContent = JSON.stringify(v); };
export const initial = () => ({ branch: 'main', autoDeploy: false, env: 'dev' });
