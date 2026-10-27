// The controller's value check (Schema.validate in json/Schema.java), ported
// for the preview. Same keywords, same JSON Pointer prefixes, same messages.
// Where it differs from the controller:
// - `pattern` runs as a JavaScript RegExp, not a Java regex, and without the
//   50 ms budget. A pattern JavaScript cannot compile is reported, not skipped.
// - Numbers are JavaScript doubles: 1e400 is Infinity and integers past 2^53
//   lose precision; the controller compares exact decimals. Messages print
//   numbers the JavaScript way (1.5, not 1.50).

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/** JSON equality as JSON Schema defines it: numbers by value, object keys in any order. */
export function equal(a, b) {
  if (Array.isArray(a)) return Array.isArray(b) && a.length === b.length && a.every((x, i) => equal(x, b[i]));
  if (isObject(a)) {
    if (!isObject(b)) return false;
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every((k) => Object.hasOwn(b, k) && equal(a[k], b[k]));
  }
  return a === b;
}

function typeOf(v) {
  if (typeof v === 'string') return 'string';
  if (typeof v === 'number') return 'number';
  if (typeof v === 'boolean') return 'boolean';
  if (Array.isArray(v)) return 'array';
  if (isObject(v)) return 'object';
  return 'null';
}

function matchesType(type, v) {
  if (Array.isArray(type)) return type.some((t) => matchesType(t, v));
  switch (type) {
    case 'string': return typeof v === 'string';
    case 'number': return typeof v === 'number';
    case 'integer': return Number.isInteger(v);
    case 'boolean': return typeof v === 'boolean';
    case 'object': return isObject(v);
    case 'array': return Array.isArray(v);
    case 'null': return v === null;
    default: return false;
  }
}

const where = (at) => `${at === '' ? '/' : at}: `;
const int = (n) => Math.trunc(Number(n));

function compile(pattern) {
  for (const flags of ['u', '']) {
    try { return new RegExp(pattern, flags); } catch { /* try without the u flag */ }
  }
  return null;
}

function check(s, v, at, errors) {
  if (typeof s === 'boolean') {
    if (!s) errors.push(`${where(at)}no value is allowed here`);
    return;
  }
  if ('type' in s && !matchesType(s.type, v)) {
    errors.push(`${where(at)}expected ${Array.isArray(s.type) ? `one of ${JSON.stringify(s.type)}` : s.type}, got ${typeOf(v)}`);
    return;
  }
  if ('enum' in s && !s.enum.some((e) => equal(e, v))) errors.push(`${where(at)}must be one of ${JSON.stringify(s.enum)}`);
  if ('const' in s && !equal(s.const, v)) errors.push(`${where(at)}must be ${JSON.stringify(s.const)}`);

  if (typeof v === 'string') {
    const length = [...v].length; // code points, as the controller counts
    if ('minLength' in s && length < int(s.minLength)) errors.push(`${where(at)}must have at least ${int(s.minLength)} characters`);
    if ('maxLength' in s && length > int(s.maxLength)) errors.push(`${where(at)}must have at most ${int(s.maxLength)} characters`);
    if ('pattern' in s) {
      const re = compile(s.pattern);
      if (!re) errors.push(`${where(at)}pattern ${s.pattern} does not compile as a JavaScript regular expression, so the preview cannot check it`);
      else if (!re.test(v)) errors.push(`${where(at)}must match ${s.pattern}`);
    }
  }
  if (typeof v === 'number') {
    if ('minimum' in s && v < s.minimum) errors.push(`${where(at)}must be >= ${s.minimum}`);
    if ('maximum' in s && v > s.maximum) errors.push(`${where(at)}must be <= ${s.maximum}`);
    if ('exclusiveMinimum' in s && v <= s.exclusiveMinimum) errors.push(`${where(at)}must be > ${s.exclusiveMinimum}`);
    if ('exclusiveMaximum' in s && v >= s.exclusiveMaximum) errors.push(`${where(at)}must be < ${s.exclusiveMaximum}`);
  }
  if (Array.isArray(v)) {
    if ('minItems' in s && v.length < int(s.minItems)) errors.push(`${where(at)}must have at least ${int(s.minItems)} items`);
    if ('maxItems' in s && v.length > int(s.maxItems)) errors.push(`${where(at)}must have at most ${int(s.maxItems)} items`);
    if (s.uniqueItems === true) {
      const dup = v.find((item, i) => v.slice(0, i).some((seen) => equal(seen, item)));
      if (dup !== undefined) errors.push(`${where(at)}has duplicate item ${JSON.stringify(dup)}`);
    }
    if ('items' in s) v.forEach((item, i) => check(s.items, item, `${at}/${i}`, errors));
  }
  if (isObject(v)) {
    const size = Object.keys(v).length;
    if ('minProperties' in s && size < int(s.minProperties)) errors.push(`${where(at)}must have at least ${int(s.minProperties)} properties`);
    if ('maxProperties' in s && size > int(s.maxProperties)) errors.push(`${where(at)}must have at most ${int(s.maxProperties)} properties`);
    for (const name of s.required ?? []) {
      if (!Object.hasOwn(v, name)) errors.push(`${where(at)}is missing required property '${name}'`);
    }
    const props = isObject(s.properties) ? s.properties : {};
    for (const [key, child] of Object.entries(v)) {
      const pointer = `${at}/${key.replaceAll('~', '~0').replaceAll('/', '~1')}`;
      if (Object.hasOwn(props, key)) check(props[key], child, pointer, errors);
      else if ('additionalProperties' in s) check(s.additionalProperties, child, pointer, errors);
    }
  }
}

/**
 * Checks a parsed value against a schema the controller accepts (check.mjs's
 * checkSchema reports the ones it refuses). Returns one message per
 * violation, each prefixed with a JSON Pointer, in the controller's order.
 */
export function validate(schema, value) {
  const errors = [];
  check(schema, value, '', errors);
  return errors;
}
