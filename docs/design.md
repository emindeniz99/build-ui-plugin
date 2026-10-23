# build-ui design

Status: agreed direction, 2026-10-23. Nothing here is implemented yet. Each
claim carries one of four labels so you can tell what we know from what we
hope:

- **Fact:** read in official docs or source code, with a link.
- **Measured:** we ran it in the lab (Chromium, Playwright 1.56.1) on
  2026-10-22/23. Firefox and Safari: not tested.
- **Assumption:** we believe it, nobody has checked it yet.
- **Proposal:** a design choice that Phase 0 still has to prove.

The project's goal and success test live in the [README](../README.md). The
original RFC is issue #94; the design here replaces it where they disagree.

## 1. Decisions

| Topic | Decision | Reason |
|---|---|---|
| Plugin ID | `build-ui`, groupId `io.jenkins.plugins`, Jenkinsfile symbol `uiParameter` | All planned surfaces belong to a run. Follows update-center naming rules, so hosting there later costs nothing. |
| Publishing | GitHub Releases (`.hpi`) first; the Jenkins update center at graduation | No hosting review on the critical path. |
| Jenkins / Java | Minimum 2.541.1, Java 17 bytecode; CI covers 2.541.x and the current LTS | 2.541 is the owner's Jenkins and the oldest LTS line with core CSP. |
| Core | Framework-agnostic; knows only the `mount` contract (section 2) | A runtime can come and go without a core rewrite. |
| v1 runtimes | `none` and `preact11-htm3` | One stable React-style renderer plus plain JS. |
| Later runtimes | `solid2-html` once Solid 2.0 ships stable and wins the comparison in section 7 | It passes strict CSP as an RC; an RC is not a production dependency. |
| Eval runtimes (Vue, Alpine) | Only behind a global admin setting, off by default | Jenkins runs CSP in report-only mode by default, so these would work for months and break on the day an admin enforces CSP. |
| Component library | None in v1; packs (Web Awesome and others) stay possible later | Both motivating forms need inputs, checkboxes and cards only. |
| Approval of form code | None in v1 (owner, 2026-10-23); an approval option goes on the roadmap before public release | Owner trusts everyone with push access on their Jenkins. |
| Surfaces, in order | Build with Parameters form, then the `input` step, then panels on the build page | The `input` step is the same form problem; build-page panels are a different product. |
| Declarative no-JS tier | After the MVP, as a module that implements `mount` | It needs nothing from the core and proves the contract with a second consumer. |
| Parked | TSX through swc4j, Remote DOM worker tier, Web Awesome pack | No current need justifies the cost. |

## 2. Module contract (Proposal)

A job module exports one async function:

```js
export default async function mount({ root, ui, runtime, signal }) {
  // render into root, read and write the value through ui
  return () => { /* cleanup */ };
}
```

- `root`: the element the host gives the parameter.
- `runtime`: the libraries for the chosen runtime ID, for example
  `{ html, render, useState, useEffect }` for `preact11-htm3`. `none` passes
  `{}`. Job code gets libraries only through `runtime`, so the page needs no
  import map.
- `signal`: an `AbortSignal`. The host aborts it when the form goes away.
- `ui`: the value channel, kept small: `value`, `setValue(next)`,
  `query(providerId, args, { signal })`. The RFC's `validate` and `subscribe`
  join only if a real form needs them.

Lifecycle rules:

1. The host loads each runtime once per page and shares it between parameters
   that use the same runtime ID.
2. The module waits for its own custom elements (`customElements.whenDefined`).
   The host cannot know which elements a module uses.
3. If the form goes away before `mount` resolves, the host aborts `signal` and
   calls the cleanup function as soon as the promise resolves.
4. If `mount` throws or rejects, the host shows the error and a JSON textarea
   with schema errors, so the user can still start a build.
5. A runtime ID carries only the major version. A patch update inside
   `preact11-htm3` keeps the ID; a new major gets a new ID, and old
   Jenkinsfiles keep working on the old one.

Authoring rule: `import` statements in job code are a lint error. This is a
dependency policy for the CLI checker, not a security control; code can still
build any URL and load it at runtime.

## 3. Trust model

Five roles meet on the form page:

| Role | Can do |
|---|---|
| Jenkinsfile author (anyone with push access) | Write the module, HTML and CSS that run in viewers' browsers |
| Viewer | Opens *Build with Parameters*; the module runs with the viewer's session and permissions |
| Admin viewer | Same as a viewer, but the session can change Jenkins itself |
| Controller | Validates values, serves modules, answers `ui.query` with the viewer's permissions |
| Approver | Does not exist in v1 |

**Accepted risk in v1:** a Jenkinsfile author can run JavaScript as any viewer,
including an admin. In a multibranch job this covers branch and PR authors
with push access, because a build writes the Jenkinsfile's parameters into the
job's configuration (Assumption: the design review cites JENKINS-41929; we
have not reproduced it). Loading
the module from a separate URL does not isolate it: it still runs on the
Jenkins origin.

**Resource Root URL** (a Jenkins setting that serves workspaces, artifacts and
`userContent` from another domain): recommended, not required. It only matters
when a module loads a same-origin file that someone else controls, such as a
workspace file. A module that wants to do harm needs no such file.

**Roadmap, before public release:** an approval option built on script-security.
`Language` is an extension point with two abstract methods,
`ScriptApproval.configuring()` puts unapproved code in the pending list, and
`using()` throws `UnapprovedUsageException` (Fact,
[Language](https://javadoc.jenkins.io/plugin/script-security/org/jenkinsci/plugins/scriptsecurity/scripts/Language.html),
[ScriptApproval](https://javadoc.jenkins.io/plugin/script-security/org/jenkinsci/plugins/scriptsecurity/scripts/ScriptApproval.html)).
The approved set would cover what runs in other people's browsers: runtime,
module, HTML, CSS and data sources. Schema and defaults only affect the build,
which the author controls anyway, so they would be validated but not approved.

## 4. CSP and module serving (Proposal)

- Strict CSP is the design target: no `unsafe-eval`, and the plugin never
  loosens Jenkins's global policy (no `jenkins.security.csp.Contributor` entry
  for `unsafe-eval`).
- The controller stores module and CSS bytes by content hash and serves them
  from a plugin endpoint with `Content-Type: text/javascript` or `text/css`,
  long-lived caching and a read-permission check on the job. The hash in the
  URL is a cache key; the server looks the bytes up and checks permission
  before it answers.
- CSS reaches the page as a `<link>` to that endpoint or through a
  constructed stylesheet (`adoptedStyleSheets`), never as an injected
  `<style>` tag.
- The CLI checker warns about `style=` and `on*=` attributes in `html`,
  because enforced CSP drops them without an error.
- Phase 0 tests enforced and report-only modes on a real Jenkins.

## 5. Validation (Proposal)

- `UiParameterDefinition` extends `SimpleParameterDefinition`, so the
  Pipeline `build` step converts string values through `createValue(String)`
  instead of failing.
- One JSON Schema check runs on the controller for every path that creates a
  value. Phase 0 tests whether a queue-level check (`QueueDecisionHandler`)
  can reject an invalid value before any build starts, which would also cover
  scheduled builds and values built in code.
- Limits: JSON size, nesting depth, array length, property count, schema size,
  and a guard on `pattern` (ReDoS). No remote `$ref` resolution (SSRF).
- `defaultJson` is validated when the job is saved; an invalid default fails
  loudly.
- `params.BUILD_CONFIG` is a JSON string. It also becomes an environment
  variable in every build process, so the plugin caps its size.
- Document POST for `buildWithParameters`: large values over GET hit Jetty's
  8 KB header limit (Assumption, from the review; not measured).

## 6. Measurements

Strict CSP (`script-src 'self' 'sha256-<import map>'`, no `unsafe-eval`). This
is our own policy, not Jenkins's real one; Phase 0 step 1 replaces it.

| Result | Libraries |
|---|---|
| Pass | vanilla JS, vanilla custom element, Preact 11.0.1 + htm 3.1.1 (hooks and signals), lit-html 3.3.3, LitElement, Solid 2.0.0-rc.17 + `@solidjs/html`, uhtml 5.0.9, Arrow 1.0.6, VanJS 1.6.1, Mithril 2.3.8, `@alpinejs/csp` 3.17.5 |
| Fail with `EvalError` | `solid-js/html` 1.9.17, Vue 3.5.44 runtime compiler, petite-vue 0.4.1, Alpine 3.17.5 |

Bundled with esbuild, minified, gzip: lit-html 3.5 KB, Preact + htm 6.5 KB,
Preact + htm + signals 8.9 KB, Solid 2.0 RC + html 20.2 KB.

Other measured behaviour:

- Enforced CSP makes `new Function()` throw. Report-only mode lets it run and
  sends one violation report per page load.
- Under enforcement, a script-created `<style>`, `setAttribute('style', …)` and
  inline `onclick` are blocked; `<link rel=stylesheet>`, `adoptedStyleSheets`
  and CSSOM property writes work.
- Same-origin dynamic `import()` works; `blob:` and `data:` imports are
  blocked. A module served with the wrong MIME type fails to load.
- Preact writes an array as an attribute (`"api,worker"`) when it renders
  before the custom element is defined, and the element then reads `[]`.
  Lit's `.value=` binding kept the array in the same test.
- Web Awesome 3.14 loads its icons with `fetch('data:…')`, which
  `connect-src 'self'` blocks.
- LitElement in plain JS: a class field for reactive state hides Lit's
  accessor, and the element stops re-rendering without an error.
- uhtml 5.0.9: toggling a conditional template threw
  `hole.n.replaceWith is not a function`.

Facts behind the measurements: Jenkins enforces UI CSP only when an admin
turns it on, since 2.539
([CSP](https://www.jenkins.io/doc/book/security/csp/)). htm's last release is
3.1.1 from October 2022. `@jsonforms/vanilla-renderers` 3.8.0 requires React.

## 7. Solid 2.0 comparison (Proposal)

Before `solid2-html` ships, Preact and Solid build the same complex form and
we compare: conditional fields, nested state, async data, multi-select
arrays, custom element upgrade timing, custom events and property binding,
removing components, cleanup, CSP, bundle size, start-up time, and how much
code the author writes. Size alone decides nothing. The custom elements are
small test elements of our own, not a component library.

## 8. Assumptions

- Jenkins's real default CSP header behaves like the lab policy for module
  scripts, stylesheets and `connect-src`.
- The *Build with Parameters* page on 2.541 and 2.580 renders a plugin's
  parameter view the same way.
- A queue-level check sees every parameter value before a build starts.

## 9. Phase 0 acceptance criteria

Phase 0 passes when a minimal plugin on Jenkins 2.541.x and 2.580.x shows:

1. The real Jenkins CSP header, copied into the lab, and every lab result
   re-run against it.
2. A module and its CSS served under enforced CSP, with the right MIME types,
   content-hash URLs and a permission check.
3. `preact11-htm3` mounting, unmounting, and handling a `mount` that rejects or
   outlives its form.
4. The JSON value reaching `params.BUILD_CONFIG`, validated, from the form,
   `buildWithParameters` and the Pipeline `build` step; invalid values
   rejected on each path.
5. The reference form in about 60 Jenkinsfile lines.

If a criterion fails, we revise this design before building on it.
