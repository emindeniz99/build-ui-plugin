// Runtime "schema-form": the declarative tier. It needs no job module; it draws
// the form from the parameter's JSON Schema and implements mount() itself.
//
// Widgets: enum -> select, boolean -> checkbox, number/integer -> number input,
// string -> text input (x-widget: "textarea" for multi-line), array of enum ->
// checkbox group, object -> fieldset. A property with
// "x-show-if": { "otherField": true } (or a list of allowed values) is shown only
// while its sibling matches, and its value is dropped while hidden.
import { h, render } from 'preact';
import { useState } from 'preact/hooks';
import htm from 'htm';

const html = htm.bind(h);

function visible(schema, siblings) {
  const rule = schema['x-show-if'];
  if (!rule) return true;
  return Object.entries(rule).every(([key, want]) =>
    Array.isArray(want) ? want.includes(siblings?.[key]) : siblings?.[key] === want);
}

function Field({ name, schema, value, onChange }) {
  const label = schema.title || name;
  const help = schema.description && html`<small class="help">${schema.description}</small>`;
  const id = `f-${name}`;
  if (schema.enum) {
    return html`<label for=${id}>${label}</label>
      <select id=${id} value=${value ?? ''} onChange=${(e) => onChange(schema.enum[e.currentTarget.selectedIndex])}>
        ${schema.enum.map((option) => html`<option value=${String(option)}>${String(option)}</option>`)}
      </select>${help}`;
  }
  if (schema.type === 'boolean') {
    return html`<label><input id=${id} type="checkbox" checked=${!!value}
      onChange=${(e) => onChange(e.currentTarget.checked)} /> ${label}</label>${help}`;
  }
  if (schema.type === 'number' || schema.type === 'integer') {
    return html`<label for=${id}>${label}</label>
      <input id=${id} type="number" step=${schema.type === 'integer' ? 1 : 'any'} value=${value ?? ''}
        onInput=${(e) => onChange(e.currentTarget.value === '' ? undefined : Number(e.currentTarget.value))} />${help}`;
  }
  if (schema.type === 'array' && schema.items?.enum) {
    const selected = Array.isArray(value) ? value : [];
    return html`<fieldset id=${id}><legend>${label}</legend>
      ${schema.items.enum.map((option) => html`<label class="choice"><input type="checkbox" value=${String(option)}
        checked=${selected.includes(option)}
        onChange=${(e) => onChange(e.currentTarget.checked
          ? schema.items.enum.filter((o) => o === option || selected.includes(o))
          : selected.filter((o) => o !== option))} /> ${String(option)}</label>`)}
      ${help}</fieldset>`;
  }
  if (schema.type === 'object') {
    return html`<fieldset id=${id}><legend>${label}</legend>
      <${ObjectFields} schema=${schema} value=${value || {}} onChange=${onChange} />${help}</fieldset>`;
  }
  const Tag = schema['x-widget'] === 'textarea' ? 'textarea' : 'input';
  return html`<label for=${id}>${label}</label>
    <${Tag} id=${id} value=${value ?? ''} onInput=${(e) => onChange(e.currentTarget.value)} />${help}`;
}

function ObjectFields({ schema, value, onChange }) {
  const entries = Object.entries(schema.properties || {});
  const update = (key, next) => {
    const copy = { ...value, [key]: next };
    if (next === undefined) delete copy[key];
    for (const [k, s] of entries) if (!visible(s, copy)) delete copy[k];
    onChange(copy);
  };
  return entries
    .filter(([, s]) => visible(s, value))
    .map(([key, s]) => html`<div class="field" key=${key}>
      <${Field} name=${key} schema=${s} value=${value[key]} onChange=${(next) => update(key, next)} /></div>`);
}

function Form({ ui }) {
  const [value, setValue] = useState(ui.value);
  const change = (next) => { setValue(next); ui.setValue(next); };
  return html`<div class="schema-form">
    <${ObjectFields} schema=${ui.schema} value=${value} onChange=${change} /></div>`;
}

const sheet = new CSSStyleSheet();
sheet.replaceSync(`
  .schema-form { display: grid; gap: .75rem; max-width: 40rem; }
  .field { display: grid; gap: .25rem; }
  fieldset { display: grid; gap: .25rem; border: 1px solid var(--input-border, #ccc); border-radius: .4rem; }
  .choice { display: flex; gap: .4rem; align-items: center; }
  .help { color: var(--text-color-secondary, #666); }
  input:not([type=checkbox]), select, textarea { font: inherit; padding: .3rem .4rem; }
`);

export function mount({ root, ui }) {
  if (!ui.schema || ui.schema.type !== 'object') {
    throw new Error('schema-form needs a schema whose type is "object"');
  }
  const shadow = root.getRootNode();
  if (shadow instanceof ShadowRoot) shadow.adoptedStyleSheets = [...shadow.adoptedStyleSheets, sheet];
  render(html`<${Form} ui=${ui} />`, root);
  return () => render(null, root);
}
