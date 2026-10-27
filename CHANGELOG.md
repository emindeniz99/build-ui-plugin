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
- Single-file forms: `html` may hold the whole form. The controller splits
  one `<script type="module">` into the module and every `<style>` into the
  CSS, and refuses classic or `src` scripts, two modules and inline event
  handlers with the reason.
- `formFrom: '<folder>'` keeps the form in the repository (`form.mjs`,
  `form.html`, `form.css`, `schema.json`, `default.json`, all optional).
  Jenkins reads the folder from the branch head of a multibranch branch job
  or a *Pipeline script from SCM* job, through the SCM API on the
  controller, so a push shows up on the next page load after a 10-second
  cache, without a build. Values are validated against the committed
  `schema.json`. Other job types and the `input` step show the reason and the
  JSON field.

### Data providers

- `jenkinsRuns`: recent builds of a readable job; with `parameter`, each run's
  past value of a build-ui parameter, never passwords or other parameters.
- `gitRefs`: branches and tags of the repositories a job uses, with glob
  filters, a `repository` glob that picks one of the job's remotes, a
  case-insensitive `search`, newest-version-first sorting, and `offset` and
  `limit` with a `total` count for paging. The `x-git-ref` schema keyword
  makes the controller reject refs that do not exist, and `x-git-repository`
  limits that check to matching remotes. Needs the Git plugin and Build
  permission. A test compares its lists with Git Parameter's for
  equivalent settings; the README lists where they differ.
- Works next to Git Parameter and Active Choices on one form, and after a
  job is renamed or moved into a folder (tested).
- `argoCdApp`: health, sync state, revision and images of an ArgoCD
  application, from servers an admin configures under Manage Jenkins » System
  » Build UI or with Configuration as Code (`unclassified.buildUi`).
- No provider accepts a URL from the browser, and credentials stay on the
  controller.

### Safety and quality

- Works with Jenkins's enforced Content Security Policy and never loosens it.
- JSON limits: 256 KiB, depth 32, 1000 items or properties; regular
  expressions in schemas run with a 50 ms budget per value.
- Glob filters (`gitRefs`, ArgoCD `apps`) match without regular
  expressions, in time bounded by glob length times name length.
- Accessible: no axe violations in the reference and parallel-envs examples,
  a `schema-form` form and the error fallback; forms work with the keyboard, typing "/" included; colors follow
  the Jenkins theme and meet 4.5:1 contrast.
- A Jenkinsfile checker: `pnpm --dir projects/jenkins-ui/js check <file>`.
- A local preview: `pnpm --dir projects/jenkins-ui/js preview <Jenkinsfile or
  formFrom folder>` renders the forms with the plugin's own `host.js` and
  runtimes under Jenkins's enforced CSP, answers `ui.query` from a JSON file,
  shows the value with the controller's verdict, and reloads when a file
  changes. No Jenkins needed.
- Types for form modules (`js/types/build-ui.d.ts`) for editors, and
  `pnpm --dir projects/jenkins-ui/js typecheck <files>`.
- Examples: a reference form, parallel environment deploys, a release form
  and a `formFrom` folder, all run by the browser test suite.

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
