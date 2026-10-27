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
- **Out of scope for v1:** a component library, TSX, and mirroring a
  sandboxed form's DOM into the page. Renderers that need eval (`alpine3`,
  `vue3`) ship off by default, behind an admin setting.
- **Known limit:** with the default settings, anyone who can push a
  Jenkinsfile can run JavaScript in the browser of every user who opens the
  form, admins included. Run it on a Jenkins where you trust everyone with
  push access, or turn on [approval of form code](#approval-of-form-code-optional)
  or [sandboxed forms](#sandboxed-forms-optional).

## Status

0.1.0 is ready to release (see [`CHANGELOG.md`](./CHANGELOG.md)). The first
tag comes after graduation, from the standalone repository. Every test passed on Jenkins 2.541.1 and 2.580.1 on 2026-10-27.
The project has 85 JenkinsRule
tests, 29 plain unit tests, 45 browser tests (Chromium) under enforced CSP
and 28 checker tests (counted 2026-10-27). CI also
runs the browser tests in Firefox and WebKit on 2.580.1. The reference
form is [`examples/reference.Jenkinsfile`](./examples/reference.Jenkinsfile)
(53 lines). Design, measurements and open questions:
[`docs/design.md`](./docs/design.md).

## Examples

| File | What it shows |
|---|---|
| [`examples/reference.Jenkinsfile`](./examples/reference.Jenkinsfile) | The ease test: conditional field, multi-select, server data and a reusable section in 53 lines |
| [`examples/parallel-envs.Jenkinsfile`](./examples/parallel-envs.Jenkinsfile) | Deploy to several test environments: cards with each environment's last build (from `jenkinsRuns` history), search, select shown, services, auto-deploy |
| [`examples/release.Jenkinsfile`](./examples/release.Jenkinsfile) | Cut a release: branch and base tag from `gitRefs` (newest version first), a preview of the next tag, a special-version override, and `x-git-ref` so the controller rejects refs that do not exist |
| [`examples/form-from/`](./examples/form-from) | A form kept in the repository with `formFrom`: one `form.html` with style, markup and module, plus `schema.json` and `default.json` |

The browser suite runs all four against a real Jenkins.

## Use it

```groovy
pipeline {
  agent any
  parameters {
    uiParameter(
      name: 'BUILD_CONFIG',
      schema: '{"type":"object","required":["branch"],"properties":{"branch":{"type":"string","minLength":1}}}',
      defaultJson: '{"branch":"main"}',
      module: '''
export default function mount({ root, ui, runtime: { html, render, useState } }) {
  function App() {
    const [c, setC] = useState(ui.value)
    const set = (next) => { setC(next); ui.setValue(next) }
    return html`<label>Branch <input value=${c.branch} onInput=${(e) => set({ ...c, branch: e.currentTarget.value })} /></label>`
  }
  render(html`<${App} />`, root)
  return () => render(null, root)
}''')
  }
  stages {
    stage('Build') {
      steps { echo "config: ${params.BUILD_CONFIG}" }
    }
  }
}
```

`params.BUILD_CONFIG` is a JSON string. Parse it with `readJSON text: params.BUILD_CONFIG`
(Pipeline Utility Steps). As with every declarative parameter, the first run
registers it; *Build with Parameters* shows the form from the second run on.

### One HTML text for the whole form

The simplest form is one `html` text with a `<style>`, the markup and one
`<script type="module">`:

```groovy
uiParameter(name: 'GREETING', html: '''
<style>.hi { font-weight: bold; }</style>
<p class="hi">Hello</p>
<script type="module">
export default function mount({ root, ui }) {
  root.querySelector('.hi').textContent = 'Hello, ' + ui.job
  ui.setValue({ greeted: true })
}
</script>''')
```

Jenkins's CSP blocks inline scripts, and no browser can load an HTML file as
a module, so the controller splits the text before it serves it. The
script's content becomes the module, every `<style>` becomes CSS (after
`css`, if you set both), and the rest stays HTML. Approval, the sandbox and
the eval setting see the parts as if you had written `module`, `css` and
`html` separately. The script must `export default function mount(...)` like
any module.

The controller refuses the form, with the reason, when the text has:

- a `<script>` without `type="module"`, or any `<script src=...>`: neither
  would run under Jenkins's CSP, and a form loads no other code;
- more than one module script, or a module script and a `module` field;
- an inline event handler such as `onclick=`, which CSP blocks; attach
  handlers in the module.

A script ends at the first `</script`, and a style at the first `</style`,
as in any HTML page.

### Forms in the repository (`formFrom`)

```groovy
uiParameter(name: 'DEPLOY', formFrom: 'forms/deploy')
```

`formFrom` names a folder relative to the repository root. Every file in it
is optional:

| File | Takes the place of |
|---|---|
| `form.mjs` | `module` |
| `form.html` | `html`, single-file form included |
| `form.css` | `css` |
| `schema.json` | `schema`; without it the controller checks only that the value is JSON |
| `default.json` | `defaultJson` |

Jenkins reads the folder from the head of the branch the job builds, on the
controller, with the job's own SCM and credentials and without a workspace
or an agent. The job decides the repository, branch and path; nothing the
browser sends picks them. [`examples/form-from/`](./examples/form-from)
shows a whole folder.

- Supported jobs: branch jobs of a multibranch Pipeline, and Pipeline jobs
  defined by *Pipeline script from SCM*. The SCM must support lightweight
  reads (the Git plugin does).
- Not supported: inline Pipeline scripts, freestyle jobs and the `input`
  step. The form then shows the reason and its JSON field, and the
  controller refuses values and defaults with the same reason. It never
  falls back to older content.
- The Jenkinsfile must not set `module`, `html`, `css`, `schema` or
  `defaultJson` next to `formFrom`. The path takes letters, digits, `.`, `_`
  and `-` between `/`, at most 200 characters, with no `..` and no leading
  `/`.
- Limits: 512 KiB each for `form.mjs`, `form.html` and `form.css`, 256 KiB
  and the JSON limits below for the JSON files, and 20 seconds per read.
- A read stays cached for 10 seconds per job and folder. The page, the
  submit check and the queue check use the same read, so they see the same
  `schema.json` within that window.
- When the build that first defines the parameter reads `params`, Pipeline
  asks the new parameter for its default, so that build reads `default.json`.
  A missing folder or a broken file then fails that build with the reason.

### When a changed form shows up

With `formFrom`, a push shows up on the next page load once the 10-second
cache has expired. No build has to run. The browser gets the new code then
too: content URLs carry the SHA-256 of the code, so no cache serves the old
version. With approval of form code on, the new version waits for approval
and the form shows its JSON field until then.

Forms written inline in the Jenkinsfile work differently. Jenkins keeps a
job's parameters in the job's configuration, and only a build that runs the
new Jenkinsfile writes them there. After you push a change to the module,
HTML, CSS or schema:

- A build has to run the new Jenkinsfile once. That build still asks for and
  uses the old form; *Build with Parameters* shows the new one afterwards.
- In a multibranch job with a webhook, the push starts that build, so the
  form updates without anyone clicking.
- Without such a trigger, run the job once, for example with its defaults.

### `uiParameter` fields

| Field | Meaning |
|---|---|
| `name` | Parameter name, as in `params.NAME` |
| `runtime` | `preact11-htm3` (default), `none`, `schema-form` or `alpine3-csp`; `alpine3` and `vue3` only where an admin allows them ([below](#runtimes-that-need-eval-off-by-default)) |
| `formFrom` | A folder of the repository that holds the form's files ([above](#forms-in-the-repository-formfrom)); excludes the five fields below it |
| `module` | An ES module whose default export is `mount` (not needed for `schema-form` or a single-file `html`) |
| `html` | Markup put into the form's root before `mount` runs; may hold the whole form ([above](#one-html-text-for-the-whole-form)) |
| `css` | Styles for the form; they apply only inside its shadow root |
| `schema` | JSON Schema the controller checks every value against (subset below) |
| `defaultJson` | The value for builds that bring none (cron, `build` without parameters) |
| `description` | Shown above the form |
| `sandbox` | `true` runs the form's code in a sandboxed iframe ([below](#sandboxed-forms-optional)); default `false` |

### The module contract

```js
export default async function mount({ root, ui, runtime, signal }) {
  // draw into root, read ui.value, write with ui.setValue
  return () => { /* cleanup */ };
}
```

| Name | What it is |
|---|---|
| `root` | The element to draw into, inside a shadow root |
| `ui.value` / `ui.setValue(next)` | The current JSON value; `setValue` changes what the form submits |
| `ui.query(id, args)` | Calls a server-side data provider as the current user; returns parsed JSON |
| `ui.job`, `ui.name`, `ui.schema` | Full name of the job, the parameter name, the parsed schema |
| `runtime` | The libraries of the chosen runtime; job code gets libraries only from here |
| `signal` | An `AbortSignal` that fires when the form goes away |

If `mount` throws, the page shows the error and a JSON textarea, so a build can
still start. If your module renders custom elements, wait for them with
`customElements.whenDefined` before you render.

Character keys typed in a field inside `root` stop at the form, so Jenkins's
`/` and Ctrl+K shortcuts do not fire while someone types. Escape, the arrow
keys, Enter and Tab reach `document` and `window` listeners as usual.

### Runtimes

- `preact11-htm3`: Preact 11.0.1 and htm 3.1.1. `runtime` holds `html`, `h`,
  `render`, `Fragment`, `createContext`, `createRef`, `cloneElement`,
  `toChildArray` and the hooks `useState`, `useEffect`, `useLayoutEffect`,
  `useMemo`, `useCallback`, `useRef`, `useReducer`, `useContext`, `useId`.
  6.8 KB gzipped.
- `none`: `runtime` is `{}`; write plain DOM code.
- `schema-form`: needs no module. It draws the form from `schema`: `enum` as a
  select, `boolean` as a checkbox, numbers as number inputs, strings as text
  inputs (`"x-widget": "textarea"` for several lines), arrays of `enum` as
  checkbox groups, objects as fieldsets. A property with
  `"x-show-if": { "deploy": true }` appears only while its sibling matches, and
  its value is dropped while hidden, from the initial value too. A boolean
  nobody touched is `false`, as its unchecked box shows.
- `alpine3-csp`: Alpine 3.17.4, CSP build. `runtime` holds `Alpine` and
  `start(root)`, which starts Alpine inside the form and returns the cleanup.
  23.3 KB gzipped. Expressions in `html` are property paths and simple calls,
  without eval; the logic lives in components the module registers with
  `Alpine.data`, as in the example below.

```groovy
uiParameter(
  name: 'DEPLOY',
  runtime: 'alpine3-csp',
  defaultJson: '{"branch":"main","deploy":false,"env":"dev"}',
  html: '''
<div x-data="deployForm">
  <label>Branch <input name="branch" x-model="branch"></label>
  <label><input type="checkbox" name="deploy" x-model="deploy"> Deploy</label>
  <label x-show="deploy">Environment
    <select name="env" x-model="env"><option>dev</option><option>prod</option></select>
  </label>
</div>''',
  module: '''
export default function mount({ root, ui, runtime: { Alpine, start } }) {
  Alpine.data('deployForm', () => ({
    ...ui.value,
    init() { for (const key of ['branch', 'deploy', 'env']) this.$watch(key, () => this.save()) },
    save() { ui.setValue({ branch: this.branch, deploy: this.deploy, env: this.env }) },
  }))
  return start(root)
}''')
```

Two rules come from how Alpine works. Return `start(root)` and never call
`Alpine.start()`: it would start Alpine on the whole Jenkins page, so the
runtime makes it throw. And `Alpine.data` names are shared by every form on
the page, so give each form its own names, for example after the parameter.

### Runtimes that need eval (off by default)

`alpine3` (Alpine 3.17.4, 19.4 KB gzipped, same `runtime` as `alpine3-csp`)
and `vue3` (Vue 3.5.44 with its template compiler, 65.6 KB gzipped, `runtime`
holds Vue's whole API) turn templates into code with eval. Jenkins's Content
Security Policy forbids eval. Jenkins sends that policy in report-only mode
unless an admin enforces it, so these runtimes work today and stop working the
day an admin enforces CSP. That is why they are off by default.

- An admin allows them with *Allow runtimes that need eval* under Manage
  Jenkins » System » Build UI, or with Configuration as Code:

  ```yaml
  unclassified:
    buildUi:
      allowEvalRuntimes: true
  ```

- While the setting is off, a form or panel with such a runtime shows a notice
  and its JSON field, and Jenkins never sends its code or loads the runtime.
- With the setting on and CSP enforced, the form says "This form's runtime
  needs eval, which this Jenkins's Content Security Policy blocks" and shows
  the JSON field. The runtime file is not loaded.
- With the setting on and CSP in report-only mode, the form works, and the
  browser reports each eval to Jenkins as a CSP violation.
- The plugin never adds `unsafe-eval` to Jenkins's policy.
- Approval of form code applies to these runtimes as to the others. A form
  whose runtime is blocked does not reach the pending list.

A Vue form puts its template in `html` and mounts the app on `root`:

```js
export default function mount({ root, ui, runtime: { createApp } }) {
  const app = createApp({ data: () => ({ ...ui.value }), watch: { branch(branch) { ui.setValue({ branch }) } } })
  app.mount(root)
  return () => app.unmount()
}
```

### Data providers

`ui.query('jenkinsRuns', { job: 'folder/name', limit: 20 })` returns the
latest builds of a job the user can read: number, display name, description,
result, building, timestamp, duration and URL. Build parameters are left out.
Add `parameter: ui.name` and each run also carries `config`, the JSON that run
got from this form (or `null`), so a form can show history without parsing
build descriptions. Only build-ui values come back this way, never passwords
or other parameter types.

`ui.query('gitRefs', { job: ui.job })` returns `{ branches: [...], tags: [...] }`,
each entry `{ name, sha, repository }`, for the Git repositories that job
already uses. The browser never sends a URL, so Jenkins only contacts servers
a job is configured for, and the job's credentials stay on the controller. It
needs Build permission on the job, works once the job has checked out at least
once, caches each answer for 30 seconds, and needs the Git plugin.

| Argument | Default | Meaning |
|---|---|---|
| `job` | required | Full name of the job, usually `ui.job` |
| `branches`, `tags` | `*` | Glob filters (`*` any run, `?` one character), for example `release/*` or `v1.*` |
| `sort` | `name` | `name`, or `version-desc` for newest version first: `v1.10.0` before `v1.9.0`, and `1.0.0` before `1.0.0-rc1` |
| `limit` | `200` | Entries per list, at most 1000 |

To make the controller reject a ref that does not exist, mark the property in
the schema: `{ "type": "string", "x-git-ref": "tag" }` (or `"branch"`, `"any"`,
`true`). The form and `buildWithParameters` answer 400 with the reason. A
build queued another way (a downstream `build` step, a timer, a script) waits
in the queue while Jenkins lists the refs, and is cancelled before it starts
if a ref does not exist or the refs cannot be listed; the reason goes to the
controller log. A slow Git server holds only that build, not the queue. A job
that has not checked out yet has no refs to compare with, so the check waits
for its first checkout; `defaultJson` is checked when a build uses it.

`ui.query('argoCdApp', { app: 'web', server: 'default' })` returns the status
of one ArgoCD application: `name`, `project`, `namespace`, `health`, `sync`,
`revision`, `operation`, `reconciledAt` and `images`. An admin adds the
servers under *Manage Jenkins* » *System* » *Build UI*: a name, the URL, a
*Secret text* credential holding an ArgoCD API token, and a glob of the
application names forms may read (`*` by default). The browser only names an
application, so Jenkins contacts only configured servers, and the token never
leaves the controller. Answers are cached for 10 seconds, and a server that
takes longer than 15 seconds to answer fails the query. With the
Configuration as Code plugin the same settings live under `unclassified.buildUi`.

```yaml
unclassified:
  buildUi:
    argoCdServers:
      - name: default
        url: https://argocd.example.com
        credentialsId: argocd-token
        apps: "web-*"
```

Other plugins can add providers by extending `io.jenkins.plugins.buildui.DataProvider`.

### Panels on the build page

```groovy
uiPanel title: 'Deploy summary', data: '{"env":"staging","services":["api"]}',
  module: 'export default function mount({ root, ui }) { root.textContent = ui.value.env }'
```

`uiPanel` takes `title`, `data` (JSON, read as `ui.value`), `module`, `html`,
`css`, `runtime` and `sandbox`, and draws on the build's page with the same contract.
`ui.job` is the build's job; `ui.schema` is `null`, because a panel has no
schema.

### `input` step

`input parameters: [uiParameter(...)]` shows the same form when a run pauses
for input, and returns the JSON string. `formFrom` does not work there; write
the form inline.

### Approval of form code (optional)

Approval is off by default, and forms run as soon as a Jenkinsfile defines
them. To review code before it runs in other people's browsers, tick
*Require approval of form code* under Manage Jenkins » System » Build UI, or
set it with Configuration as Code:

```yaml
unclassified:
  buildUi:
    requireApproval: true
```

With approval on:

- A form or panel with a `module`, `html` or `css` shows a notice and the JSON
  field until an admin approves its exact code under Manage Jenkins »
  In-process Script Approval, listed as a "Build UI form" script. Jenkins
  never sends unapproved code to a browser.
- Builds keep working while code waits: people type the JSON, and the
  controller validates it against the schema as before.
- Any change to the runtime, module, HTML or CSS needs a new approval. The
  pending list holds one entry per form, so a new push replaces the older
  pending version.
- A `schema-form` parameter with no module, HTML or CSS needs no approval.
- The code goes to the pending list the first time someone opens the form.
  Code an admin denies stays hidden and is not listed again until it changes
  or Jenkins restarts.
- Like Groovy scripts, everything counts as approved when Jenkins security is
  off. With *Force sandbox* on, only an admin opening the form adds it to the
  pending list; views by other users before or after leave the list as it is.

### Sandboxed forms (optional)

A form or panel with `sandbox: true` runs its module, HTML and CSS inside
`<iframe sandbox="allow-scripts">` instead of on the Jenkins page. To sandbox
every form, an admin ticks *Run all form code in a sandbox* under Manage
Jenkins » System » Build UI, or sets it with Configuration as Code. Both are
off by default.

```yaml
unclassified:
  buildUi:
    sandboxAll: true
```

The module keeps the same contract: `root` sits in a shadow root, `ui.value`,
`ui.setValue`, `ui.query`, `ui.job`, `ui.name`, `ui.schema`, `runtime` and
`signal` work as on the page, and the frame grows with its content. What
changes is what the code can reach. The frame has an origin of its own, so
in Chromium the code:

- cannot read or change the Jenkins page (`parent.document` throws);
- cannot read cookies (`document.cookie` throws), and its own requests to
  Jenkins carry none;
- cannot call the Jenkins API with `fetch`, because Jenkins sends no CORS
  headers to the frame;
- cannot navigate the page;
- under Jenkins's CSP, cannot fetch from another host, load an image from
  one, or navigate its frame to one.

The `sandbox` attribute also withholds pop-ups and form submission (HTML
standard; not tested here).

It reaches Jenkins only through `ui.query`, which the page runs for it with
your session, as it does for a form outside the sandbox. The submitted
value still goes through the JSON field and the controller's schema check.

What the sandbox does not stop:

- The code can draw anything inside its own frame, including text that
  misleads you.
- The code can read through `ui.query` whatever you can read and put it into
  the value you submit. The build, which the Jenkinsfile author controls, then
  receives it.
- Browsers have channels that Jenkins's CSP does not cover, such as WebRTC.
  We have not tested whether a sandboxed form can send data out through them.
- Only Chromium is tested. Firefox and Safari should behave the same, but
  nobody has checked.

With [approval](#approval-of-form-code-optional) on, a sandboxed form still
waits for an admin. Code approved for the page may also run in the sandbox;
code approved only for the sandbox needs a fresh approval once its
Jenkinsfile drops `sandbox: true`.

For authors: the frame is a separate document, so styles and theme come from
`css` and from the Jenkins theme colors the page copies into the frame when
it loads. A theme change after that does not reach the frame.

### Validation and limits

The controller checks every value, whatever started the build: the form,
`buildWithParameters`, the `build` step, and a queue-level check for values
made in code. An invalid value gets HTTP 400 with the reason, or fails the
`build` step with it. A `formFrom` value queued another way (a `build` step,
a timer, a script) waits in the queue while the controller reads
`schema.json`, as for `x-git-ref` below, and is cancelled if it fails.

- JSON: at most 256 KiB, nesting depth 32, 1000 items per array, 1000
  properties per object, no duplicate keys.
- Schema keywords: `type`, `enum`, `const`, `required`, `properties`,
  `additionalProperties`, `minProperties`, `maxProperties`, `items`,
  `minItems`, `maxItems`, `uniqueItems`, `minLength`, `maxLength`, `pattern`,
  `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, plus
  annotations and `x-` extensions. Any other keyword, `$ref` included, fails
  when the job is configured.
- `pattern` is a Java regular expression. All patterns in one document
  share a 50 ms budget; past it the value is rejected.
- `enum`, `const` and `uniqueItems` compare numbers by value, so `5.0`
  equals `5`.
- Values over 32 KiB stay in `params` but do not become an environment
  variable of build processes.
- Send large values to `buildWithParameters` in the POST body: a 32 KiB value
  in the query string did not start a build in our tests.

### Check a Jenkinsfile before you push it

```bash
pnpm --dir projects/jenkins-ui/js install   # once; the checker needs esbuild
pnpm --dir projects/jenkins-ui/js check path/to/Jenkinsfile
```

Paths are relative to the directory you run the command from. A `formFrom`
path is relative to the repository root: the Git top level of the
Jenkinsfile, or its directory outside Git. When that folder exists on disk,
the checker reads its files and checks them like inline fields; when it
does not, you get a warning.

The checker finds every `uiParameter` and `uiPanel` call and reports, with
Jenkinsfile line numbers: module syntax errors, `import` statements, a missing
`export default`, the single-file rules for `html` (classic or `src`
scripts, two module scripts, inline handlers), a bad `formFrom` path or
inline fields next to it, `<script>` and inline handlers in a panel's `html`, schema keywords
and keyword values the controller rejects, invalid JSON in `schema`,
`defaultJson` and `data` (duplicate keys and the limits above included),
Groovy escapes such as `\d` that Jenkins cannot compile, unknown
runtimes, a `sandbox` that is not `true` or `false`, and `sandbox: true`
on a form with no code to sandbox. A `pattern` that JavaScript cannot compile gets a warning, since
the controller compiles it as Java. It warns about runtimes that need eval. It reads string literals only; a field built from
variables or a `"${…}"` GString gets a warning. It does not check `defaultJson`
against the schema; the controller does that when a build uses the default. A
file with no `uiParameter` or `uiPanel` call fails the check.

### CSP and styling

The plugin works with Jenkins's enforced policy and never weakens it. Inline
event handlers (`onclick="…"`) do not run: a parameter refuses them, and in a
panel's `html` they stay dead. Attach handlers in the module. Put styles in
`css` or in a `<style>` of a single-file `html`. Recommended: set Jenkins's Resource Root URL, so
workspace files and artifacts are not served from the Jenkins origin.

## How to run

You need Java 17 or newer and Maven 3.9; the JS tooling uses Node 24.22.1 and
pnpm 12.14.0 (`js/package.json`).

```bash
mvn -f projects/jenkins-ui/pom.xml verify          # build and test on 2.541.1
mvn -f projects/jenkins-ui/pom.xml hpi:run \
  -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true   # Jenkins at http://localhost:8080/jenkins/
pnpm --dir projects/jenkins-ui/js install
pnpm --dir projects/jenkins-ui/js e2e               # browser tests against hpi:run
pnpm --dir projects/jenkins-ui/js vendor            # rebuild runtime bundles after editing js/runtimes
```

The installable plugin is `target/build-ui.hpi`. Upload it under *Manage
Jenkins* » *Plugins* » *Advanced settings*.

### Release

Push a tag `jenkins-ui-v<major>.<minor>.<patch>` on a commit of `main`. The
`jenkins-ui release` workflow builds the plugin at that version, runs its
tests, and attaches `build-ui.hpi` and its SHA-256 to a GitHub Release with
`CHANGELOG.md` as the notes. Update `CHANGELOG.md` first.

Releases start after graduation (owner, 2026-10-27). In this monorepo the
workflows are written for the standalone repository and nobody runs them by
hand. After the split:

1. Push the standalone repository and check that Actions is enabled and the
   default token is read-only.
2. Edit the three `jenkins-ui-*.yml` workflows for the new layout: drop the
   `projects/jenkins-ui` paths, the working directories and the gitleaks
   path list.
3. Let `jenkins-ui CI` and `jenkins-ui security` pass on `main`, Firefox and
   WebKit included. They have never run.
4. Push the `v0.1.0` tag (the tag prefix changes with the layout) and check
   that the Release carries `build-ui.hpi` and its checksum.

`jenkins-ui security` lints the workflows with zizmor and scans the
project's history for secrets with gitleaks. Both pass locally: zizmor 1.31.1
found nothing in the three workflows, and gitleaks v8.30.1 found no leaks in
54 commits (2026-10-27).

## Notes / learnings

- [`MEMORY.md`](./MEMORY.md) holds the current state and the decisions log.
- [`js/lab/`](./js/lab) holds the renderer lab behind the design's measurements.
