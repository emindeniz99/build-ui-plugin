# jenkins-ui

A Jenkins plugin (plugin ID `build-ui`) that lets a Jenkinsfile define a rich,
reactive build form in HTML, CSS and browser-native JavaScript, with no
frontend build step.

## Why

- **Problem:** Jenkins jobs need build forms that react to the user: hide a
  field when a box is unticked, load options from the server, pick several
  targets at once. Active Choices does this today with unsandboxed Groovy that
  returns HTML. It depends on Jenkins's internal page structure and drops its
  values when a build starts from the REST API.
- **Success:** one Jenkinsfile holds the whole form. Jenkins renders it inside
  *Build with Parameters* with Content Security Policy enforced, and the
  validated JSON reaches `params.BUILD_CONFIG` from the form, the REST API and
  the Pipeline `build` step.
- **Ease test:** a reference form with a conditional field, a multi-select,
  server data and a reusable section fits in about 60 Jenkinsfile lines.
- **Out of scope for v1:** approval of form code, a component library,
  renderers that need `unsafe-eval`, TSX, and sandboxing untrusted code.
- **Known limit:** v1 has no approval step, so anyone who can push a
  Jenkinsfile can run JavaScript in the browser of every user who opens the
  form, admins included. Run it only on a Jenkins where you trust everyone
  with push access.

## Status

Design stage. Phase 0, a minimal plugin that proves CSP, module serving and
value delivery on a real Jenkins, comes next. The design, measurements and
open questions live in [`docs/design.md`](./docs/design.md). The original RFC
is issue #94 in this repo.

## How to run

Nothing runs yet. Once code lands you will need Java 17 or newer and
Maven 3.9. The plugin targets Jenkins 2.541.1 and newer.

## Notes / learnings

- [`MEMORY.md`](./MEMORY.md) holds the current state and the decisions log.
