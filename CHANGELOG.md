# Changelog

## 0.1.0

The first release. It needs Jenkins 2.541.1 or newer and Java 17.

### Forms

- `uiParameter`: a build parameter whose form the Jenkinsfile writes as an ES
  module with HTML and CSS. The value is one JSON string in `params.NAME`,
  validated on the controller against a JSON Schema subset on every path: the
  form, `buildWithParameters`, the Pipeline `build` step and the build queue.
- Runtimes: `preact11-htm3` (Preact 11.0.1, htm 3.1.1), `none` (plain DOM),
  `schema-form` (a form drawn from the schema alone, with `x-show-if`) and
  `alpine3-csp` (Alpine 3.17.4, CSP build). Alpine starts only inside the
  form; `Alpine.start()` throws.
- Works in Pipeline and freestyle jobs, in `input` steps
  (`input parameters: [uiParameter(...)]`), and as panels on the build page
  (`uiPanel` step).
- Two forms on one page share one download of their runtime.

### Data providers

- `jenkinsRuns`: recent builds of a readable job; with `parameter`, each run's
  past value of a build-ui parameter, never passwords or other parameters.
- `gitRefs`: branches and tags of the repositories a job uses, with glob
  filters, newest-version-first sorting and a limit. The `x-git-ref` schema
  keyword makes the controller reject refs that do not exist. Needs the Git
  plugin and Build permission.
- `argoCdApp`: health, sync state, revision and images of an ArgoCD
  application, from servers an admin configures under Manage Jenkins » System
  » Build UI or with Configuration as Code (`unclassified.buildUi`).
- No provider accepts a URL from the browser, and credentials stay on the
  controller.

### Safety and quality

- Works with Jenkins's enforced Content Security Policy and never loosens it.
- JSON limits: 256 KiB, depth 32, 1000 items or properties; regular
  expressions in schemas run with a 50 ms budget per value.
- Accessible: no axe violations in the shipped runtimes, examples and error
  fallback; forms work with the keyboard, typing "/" included; colors follow
  the Jenkins theme and meet 4.5:1 contrast.
- A Jenkinsfile checker: `pnpm --dir projects/jenkins-ui/js check <file>`.
- Examples: a reference form, parallel environment deploys and a release
  form, all run by the browser test suite.

### Runtimes that need eval (optional)

- `alpine3` (Alpine 3.17.4) and `vue3` (Vue 3.5.44 with its template
  compiler), off by default. An admin allows them with *Allow runtimes that
  need eval* or `unclassified.buildUi.allowEvalRuntimes`. They work only
  while Jenkins sends CSP in report-only mode.
- While the setting is off, their forms and panels show a notice and the
  JSON field, and Jenkins never sends their code. With the setting on and CSP
  enforced, the form says its runtime needs eval and shows the JSON field.
- The checker warns about these runtimes.

### Approval (optional)

- *Require approval of form code*, off by default: an admin approves each
  form's exact code under In-process Script Approval before Jenkins sends it
  to a browser. Until then the form shows its JSON field, and builds keep
  working. Configuration as Code: `unclassified.buildUi.requireApproval`.

Known limit: with approval off, anyone who can push a Jenkinsfile can run
JavaScript in the browser of every user who opens the form.
