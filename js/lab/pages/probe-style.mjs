// Reviewer probe: what a host/job module can and cannot do under the lab's strict CSP.
const r = {};
const app = document.getElementById('app'); const out = document.getElementById('out');
// 1. <style> element created by JS (what a naive "css:" field injection would do)
try { const s = document.createElement('style'); s.textContent = '#app{color:rgb(1,2,3)}'; document.head.append(s); r.styleElement = getComputedStyle(app).color; } catch (e) { r.styleElement = 'throw ' + e.message; }
// 2. constructable stylesheet + adoptedStyleSheets
try { const sh = new CSSStyleSheet(); sh.replaceSync('#out{color:rgb(4,5,6)}'); document.adoptedStyleSheets = [sh]; r.adoptedSheet = getComputedStyle(out).color; } catch (e) { r.adoptedSheet = 'throw ' + e.message; }
// 3. CSSOM property set (what Preact/Lit do for style=${{...}})
app.style.backgroundColor = 'rgb(7,8,9)'; r.cssomProp = getComputedStyle(app).backgroundColor;
// 4. style="" attribute string (what htm/Preact do for style="..." string, and what setAttribute does)
out.setAttribute('style', 'background-color: rgb(10,11,12)'); r.styleAttr = getComputedStyle(out).backgroundColor;
// 5. <link rel=stylesheet> added by JS, same origin
await new Promise(res => { const l = document.createElement('link'); l.rel = 'stylesheet'; l.href = '/pages/app.css'; l.onload = () => { r.linkSelf = 'loaded'; res(); }; l.onerror = () => { r.linkSelf = 'error'; res(); }; document.head.append(l); });
// 6. dynamic import of a same-origin module (how the host would load an approved job module)
try { const m = await import('/pages/common.mjs'); r.importSelf = typeof m.publish; } catch (e) { r.importSelf = 'fail ' + e.constructor.name; }
// 7. blob: module (tempting shortcut: build a module from the inline Jenkinsfile string in the page)
try { const u = URL.createObjectURL(new Blob(['export const x=1'], { type: 'text/javascript' })); const m = await import(u); r.importBlob = m.x; } catch (e) { r.importBlob = 'fail ' + e.constructor.name; }
// 8. data: module
try { const u = ['data:text/javascript', 'export const x=2'].join(','); const m = await import(u); r.importData = m.x; } catch (e) { r.importData = 'fail ' + e.constructor.name; }
// 9. module served with a non-JS MIME type (what a Stapler endpoint returning text/plain would do)
try { await import('/pages/app.css'); r.importWrongMime = 'loaded'; } catch (e) { r.importWrongMime = 'fail ' + e.constructor.name + ': ' + e.message.slice(0, 70); }
// 10. inline event handler attribute (what html: field with onclick="" would do)
app.insertAdjacentHTML('beforeend', '<button id="b" onclick="window.__clicked=1">x</button>'); document.getElementById('b').click(); r.inlineHandler = window.__clicked === 1 ? 'ran' : 'blocked';
out.textContent = JSON.stringify(r);
