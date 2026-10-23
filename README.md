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
- **Out of scope for v1:** a component library, renderers that need
  `unsafe-eval`, TSX, and sandboxing untrusted code.
- **Known limit:** with the default settings, anyone who can push a
  Jenkinsfile can run JavaScript in the browser of every user who opens the
  form, admins included. Run it on a Jenkins where you trust everyone with
  push access, or turn on [approval of form code](#approval-of-form-code-optional).

## Status

0.1.0 is ready to release (see [`CHANGELOG.md`](./CHANGELOG.md)); no tag is
pushed yet. Tested on Jenkins 2.541.1 and 2.580.1: 59 JenkinsRule tests and 18
browser tests (Chromium) under enforced CSP, plus 11 checker tests. CI also
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

The browser suite runs all three against a real Jenkins.

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

### `uiParameter` fields

| Field | Meaning |
|---|---|
| `name` | Parameter name, as in `params.NAME` |
| `runtime` | `preact11-htm3` (default), `none` or `schema-form` |
| `module` | An ES module whose default export is `mount` (not needed for `schema-form`) |
| `html` | Markup put into the form's root before `mount` runs |
| `css` | Styles for the form; they apply only inside its shadow root |
| `schema` | JSON Schema the controller checks every value against (subset below) |
| `defaultJson` | The value for builds that bring none (cron, `build` without parameters) |
| `description` | Shown above the form |

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
`css` and `runtime`, and draws on the build's page with the same contract.
`ui.job` is the build's job; `ui.schema` is `null`, because a panel has no
schema.

### `input` step

`input parameters: [uiParameter(...)]` shows the same form when a run pauses
for input, and returns the JSON string.

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

### Validation and limits

The controller checks every value, whatever started the build: the form,
`buildWithParameters`, the `build` step, and a queue-level check for values
made in code. An invalid value gets HTTP 400 with the reason, or fails the
`build` step with it.

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
pnpm --dir projects/jenkins-ui/js check path/to/Jenkinsfile
```

The checker finds every `uiParameter` and `uiPanel` call and reports, with
Jenkinsfile line numbers: module syntax errors, `import` statements, a missing
`export default`, `<script>` and inline handlers in `html`, schema keywords
the controller rejects, invalid JSON in `schema`, `defaultJson` and `data`,
and unknown runtimes. It reads string literals only; a field built from
variables or a `"${…}"` GString gets a warning. It does not check `defaultJson`
against the schema; the controller does that when a build uses the default.

### CSP and styling

The plugin works with Jenkins's enforced policy and never weakens it. Inline
event handlers (`onclick="…"`) in `html` do not run; attach handlers in the
module. Put styles in `css`. Recommended: set Jenkins's Resource Root URL, so
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

## Notes / learnings

- [`MEMORY.md`](./MEMORY.md) holds the current state and the decisions log.
- [`js/lab/`](./js/lab) holds the renderer lab behind the design's measurements.
