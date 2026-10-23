// What the host would run before loading an eval-based runtime.
const reports = [];
document.addEventListener('securitypolicyviolation', e => reports.push(e.disposition + ':' + e.violatedDirective));
let evalAllowed;
try { new Function('return 1')(); evalAllowed = true; } catch { evalAllowed = false; }
setTimeout(() => { document.getElementById('out').textContent = JSON.stringify({ evalAllowed, reports }); }, 100);
