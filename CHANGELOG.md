# Changelog

## 0.1.0

The first release. It needs Jenkins 2.541.1 or newer and Java 17.

### Forms

- `uiParameter`: a build parameter whose form the Jenkinsfile writes as an ES
  module with HTML and CSS. The value is one JSON string in `params.NAME`,
  validated on the controller against a JSON Schema subset on every path: the
  form, `buildWithParameters`, the Pipeline `build` step and the build queue.
- Runtimes: `preact11-htm3` (Preact 11.0.1, htm 3.1.1), `none` (plain DOM)
  and `schema-form` (a form drawn from the schema alone, with `x-show-if`).
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

### Approval (optional)

- *Require approval of form code*, off by default: an admin approves each
  form's exact code under In-process Script Approval before Jenkins sends it
  to a browser. Until then the form shows its JSON field, and builds keep
  working. Configuration as Code: `unclassified.buildUi.requireApproval`.

### Sandbox (optional)

- `sandbox: true` on `uiParameter` or `uiPanel` runs the form's code in
  `<iframe sandbox="allow-scripts">`: no cookies, no Jenkins API, no access to
  the page. The code reaches Jenkins only through `ui.query`, which the page
  runs with the viewer's session. Works for build forms, `input` steps and
  panels.
- *Run all form code in a sandbox*, off by default, sandboxes every form with
  code. Configuration as Code: `unclassified.buildUi.sandboxAll`.
- With approval on, sandboxed forms still need it; an approval given only for
  the sandbox does not carry over when a Jenkinsfile drops `sandbox: true`.
- Tested in Chromium only.

Known limit: with approval and the sandbox off, anyone who can push a
Jenkinsfile can run JavaScript in the browser of every user who opens the
form. A sandboxed form can still show misleading content in its frame and put
data the viewer can read through `ui.query` into the submitted value.
