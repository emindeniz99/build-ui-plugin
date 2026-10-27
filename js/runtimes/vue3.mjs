// Runtime "vue3": Vue 3's ESM browser build with the template compiler, so a
// form can use an in-DOM template (the html field) or a template string. The
// compiler turns templates into code with eval, so this runtime works only
// while Jenkins sends CSP in report-only mode.
export * from 'vue/dist/vue.esm-browser.prod.js';
