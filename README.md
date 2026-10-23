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

v0.1.0-SNAPSHOT, not released. Phase 0 passed on Jenkins 2.541.1 and 2.580.1:
32 JenkinsRule tests and 8 browser tests under enforced CSP. The reference
form is [`examples/reference.Jenkinsfile`](./examples/reference.Jenkinsfile)
(53 lines). Design, measurements and open questions:
[`docs/design.md`](./docs/design.md).

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
  its value is dropped while hidden.

### Data providers

`ui.query('jenkinsRuns', { job: 'folder/name', limit: 20 })` returns the
latest builds of a job the user can read: number, display name, description,
result, building, timestamp, duration and URL. Build parameters are left out.
Other plugins can add providers by extending `io.jenkins.plugins.buildui.DataProvider`.

### Panels on the build page

```groovy
uiPanel title: 'Deploy summary', data: '{"env":"staging","services":["api"]}',
  module: 'export default function mount({ root, ui }) { root.textContent = ui.value.env }'
```

`uiPanel` takes `title`, `data` (JSON, read as `ui.value`), `module`, `html`,
`css` and `runtime`, and draws on the build's page with the same contract.

### `input` step

`input parameters: [uiParameter(...)]` shows the same form when a run pauses
for input, and returns the JSON string.

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
- `pattern` is a Java regular expression with a 50 ms budget per value.
- Values over 32 KiB stay in `params` but do not become an environment
  variable of build processes.
- Send large values to `buildWithParameters` in the POST body: a 32 KiB value
  in the query string did not start a build in our tests.

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
mvn -f projects/jenkins-ui/pom.xml hpi:run          # Jenkins at http://localhost:8080/jenkins/
pnpm --dir projects/jenkins-ui/js install
pnpm --dir projects/jenkins-ui/js e2e               # browser tests against hpi:run
pnpm --dir projects/jenkins-ui/js vendor            # rebuild runtime bundles after editing js/runtimes
```

The installable plugin is `target/build-ui.hpi`. Upload it under *Manage
Jenkins* » *Plugins* » *Advanced settings*.

## Notes / learnings

- [`MEMORY.md`](./MEMORY.md) holds the current state and the decisions log.
- [`js/lab/`](./js/lab) holds the renderer lab behind the design's measurements.
