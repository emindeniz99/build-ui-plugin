// Runtime "schema-form": the declarative tier. It needs no job module; it draws
// the form from the parameter's JSON Schema and implements mount() itself.
//
// Widgets: enum -> select, boolean -> checkbox, number/integer -> number input,
// string -> text input (x-widget: "textarea" for multi-line), array of enum ->
// checkbox group, object -> fieldset. A property with
// "x-show-if": { "otherField": true } (or a list of allowed values) is shown only
// while its sibling matches, and its value is dropped while hidden.
// The value submitted is the value shown, from the first render on: a missing
// boolean is an unchecked box, so it becomes false, and hidden properties in the
// initial value are dropped too.
import { h, render } from 'preact';
import { useId, useState } from 'preact/hooks';
import htm from 'htm';

const html = htm.bind(h);

function visible(schema, siblings) {
  const rule = schema['x-show-if'];
  if (!rule) return true;
  return Object.entries(rule).every(([key, want]) =>
    Array.isArray(want) ? want.includes(siblings?.[key]) : siblings?.[key] === want);
}

// The object as the form shows it: missing booleans false, hidden properties
// dropped, nested objects that exist settled the same way.
function settle(schema, value) {
  const out = { ...value };
  const entries = Object.entries(schema.properties || {});
  for (const [k, s] of entries) if (s.type === 'boolean' && out[k] === undefined) out[k] = false;
  for (const [k, s] of entries) {
    if (!visible(s, out)) delete out[k];
    else if (s.type === 'object' && out[k] && typeof out[k] === 'object') out[k] = settle(s, out[k]);
  }
  return out;
}

// Keeps the text being typed ("0.0" on the way to "0.05") while it still means the
// current value; Number("0.0") is 0, and writing 0 back would erase the ".0".
function NumberInput({ id, schema, value, onChange }) {
  const [text, setText] = useState(value ?? '');
  const shown = text !== '' && Number(text) === value ? text : (value ?? '');
  return html`<input id=${id} type="number" step=${schema.type === 'integer' ? 1 : 'any'} value=${shown}
    onInput=${(e) => { const t = e.currentTarget.value; setText(t); onChange(t === '' ? undefined : Number(t)); }} />`;
}

function Field({ name, schema, value, onChange }) {
  const label = schema.title || name;
  const help = schema.description && html`<small class="help">${schema.description}</small>`;
  // Unique per field: nested objects may repeat a key, and a label must name its own input.
  const id = useId();
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
      <${NumberInput} id=${id} schema=${schema} value=${value} onChange=${onChange} />${help}`;
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

function ObjectFields({ schema, value: given, onChange }) {
  const value = settle(schema, given);
  const update = (key, next) => {
    const copy = { ...value, [key]: next };
    if (next === undefined) delete copy[key];
    onChange(settle(schema, copy));
  };
  return Object.entries(schema.properties || {})
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
  /* Jenkins 2.541 --text-color-secondary is under 4.5:1 on its background; see host.js. */
  .help { color: color-mix(in oklch, var(--text-color-secondary, #666) 60%, var(--text-color, #000)); }
  input:not([type=checkbox]), select, textarea { font: inherit; padding: .3rem .4rem; }
`);

export function mount({ root, ui }) {
  if (!ui.schema || ui.schema.type !== 'object') {
    throw new Error('schema-form needs a schema whose type is "object"');
  }
  const shadow = root.getRootNode();
  if (shadow instanceof ShadowRoot) shadow.adoptedStyleSheets = [...shadow.adoptedStyleSheets, sheet];
  const initial = settle(ui.schema, ui.value);
  if (JSON.stringify(initial) !== JSON.stringify(ui.value)) ui.setValue(initial);
  render(html`<${Form} ui=${ui} />`, root);
  return () => render(null, root);
}
