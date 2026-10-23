// Runtime "alpine3-csp": Alpine's CSP build. It reads x- attributes without
// eval, so it works under an enforced Content Security Policy. Expressions are
// property paths and simple calls; logic lives in components the module
// registers with Alpine.data.
import Alpine from '@alpinejs/csp';

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
