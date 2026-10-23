# Changelog

## 0.1.0

The first release. It needs Jenkins 2.541.1 or newer and Java 17.

- `uiParameter`: a build parameter whose form the Jenkinsfile writes as an ES
  module with HTML and CSS. The value is one JSON string in `params.NAME`,
  validated on the controller against a JSON Schema subset on every path: the
  form, `buildWithParameters`, the Pipeline `build` step and the build queue.
- Runtimes: `preact11-htm3` (Preact 11.0.1, htm 3.1.1), `none` (plain DOM)
  and `schema-form` (a form drawn from the schema alone, with `x-show-if`).
- `uiPanel` step: panels on the build page with the same module contract.
- `input` step support: `input parameters: [uiParameter(...)]`.
- Data providers: `jenkinsRuns` (recent builds of a readable job) and
  `gitRefs` (branches and tags of the repositories a job uses; needs the Git
  plugin and Build permission).
- A Jenkinsfile checker: `pnpm --dir projects/jenkins-ui/js check <file>`.
- Works with Jenkins's enforced Content Security Policy and never loosens it.

Known limit: there is no approval step for form code. Anyone who can push a
Jenkinsfile can run JavaScript in the browser of every user who opens the
form.
