// Runtime "alpine3": full Alpine, which compiles x- expressions with eval.
// It works only while Jenkins sends CSP in report-only mode; prefer
// alpine3-csp. Same API as alpine3-csp: { Alpine, start(root) }.
import Alpine from 'alpinejs';

// Alpine.start() initialises and observes the whole document, which here is
// the Jenkins page, and it never reaches into a form's shadow root.
Alpine.start = () => {
  throw new Error('Alpine.start() would run Alpine on the whole Jenkins page; return start(root) from mount instead');
};

export { Alpine };

/** Starts Alpine inside root only; returns the cleanup that stops it again. */
export function start(root) {
  Alpine.initTree(root);
  return () => Alpine.destroyTree(root);
}
