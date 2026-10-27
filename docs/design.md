# build-ui design

Status: Phase 0 passed on 2026-10-23 on Jenkins 2.541.1 and 2.580.1 (section
9). Each claim carries one of these labels so you can tell what we know from
what we hope:

- **Fact:** read in official docs or source code, with a link.
- **Measured:** we ran it in the lab or against a real Jenkins (Chromium,
  Playwright 1.56.1) on 2026-10-22/23. Firefox and Safari: not tested.
- **Tested:** built, and a JenkinsRule or browser test in this project
  covers it.
- **Assumption:** we believe it, nobody has checked it yet.
- **Proposal:** a design choice not built yet.

The project's goal and success test live in the [README](../README.md). The
project began as a written design request, called the RFC below, which is not
part of this repository. Where the two disagree, this document wins.

## 1. Decisions

| Topic | Decision | Reason |
|---|---|---|
| Plugin ID | `build-ui`, groupId `io.jenkins.plugins`, Jenkinsfile symbol `uiParameter` | All planned surfaces belong to a run. Follows update-center naming rules, so hosting there later costs nothing. |
| Publishing | GitHub Releases (`.hpi`) first; the Jenkins update center later | No hosting review on the critical path. |
| Jenkins / Java | Minimum 2.541.1, Java 17 bytecode; CI covers 2.541.x and the current LTS | 2.541 is the oldest LTS line with core CSP. |
| Core | Framework-agnostic; knows only the `mount` contract (section 2) | A runtime can come and go without a core rewrite. |
| v1 runtimes | `none`, `preact11-htm3`, `schema-form` and `alpine3-csp` | One stable React-style renderer, plain JS, a no-JS tier, and Alpine's CSP build, which passed strict CSP in section 6. |
| Later runtimes | `solid2-html` not planned: it lost the comparison in section 7 | Four times larger, and breaks plain custom elements even when they are defined first. |
| Eval runtimes (Vue, Alpine) | `alpine3` and `vue3` ship behind `allowEvalRuntimes`, a global admin setting, off by default (section 12) | Jenkins runs CSP in report-only mode by default, so these would work for months and break on the day an admin enforces CSP. The plugin never adds `unsafe-eval` to the policy. |
| Component library | None in v1; packs (Web Awesome and others) stay possible later | Both motivating forms need inputs, checkboxes and cards only. |
| Approval of form code | An admin setting, off by default (owner, 2026-10-23), built on script-security | The owner trusts everyone with push access and wants no friction by default; other Jenkins setups can opt in. |
| Sandbox for form code | Opt-in: `sandbox: true` on `uiParameter` and `uiPanel`, or the admin setting *Run all form code in a sandbox* (`sandboxAll`, JCasC). Off by default. The code renders in `<iframe sandbox="allow-scripts">` (owner, 2026-10-23: isolated iframe, not DOM mirroring). Section 12. | An opaque-origin frame has no cookies, no Jenkins API and no access to the page, and needs no change to Jenkins's CSP. Mirroring a worker's DOM into the page would need a renderer of our own and is not built. |
| Surfaces, in order | Build with Parameters form, then the `input` step, then panels on the build page (`uiPanel` step). All three are built. | The `input` step is the same form problem; build-page panels are a different product. |
| Declarative no-JS tier | Built as the `schema-form` runtime, which exports `mount` itself, so a job needs no module | It needs nothing from the core and proves the contract with a second consumer. |
| Data providers | `DataProvider` extension point; ships `jenkinsRuns` (Item/Read), `gitRefs` (Item/Build, optional Git plugin) and `argoCdApp` (admin-configured servers) | No provider takes a URL from the browser: `gitRefs` takes a job, plus a glob that picks among that job's remotes (section 16); `argoCdApp` takes a server name and an app name matched against an admin glob. That rules out SSRF through providers, and credentials stay on the controller. |
| Git Parameter parity | `gitRefs` gains `repository`, `search`, `offset` and a `total` count; `x-git-repository` narrows `x-git-ref`. A JenkinsRule test compares both plugins' lists on one repository (section 16) | The RFC: "do not replace it until parity is demonstrated". |
| Forms kept in the repository | `formFrom: '<folder>'` reads `form.mjs`, `form.html`, `form.css`, `schema.json` and `default.json` from the head of the job's branch through the SCM API (owner, 2026-10-27); section 14 | Jenkins stores parameter definitions in the job, so an inline form changes only after a build runs the new Jenkinsfile. Reading the branch head shows a push on the next page load. |
| Single-file form | One `<script type="module">` and any `<style>` in `html` or `form.html` are split out on the controller (owner, 2026-10-27); section 14 | No browser loads HTML modules, and Jenkins's CSP blocks inline scripts. Design choice, not measured here. |
| Parked | TSX through swc4j, Remote DOM (a worker whose DOM the page mirrors), Web Awesome pack | No current need justifies the cost; the iframe sandbox covers isolation. |

## 2. Module contract (Tested)

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
  `query(providerId, args, { signal })`, plus read-only `job`, `name` and
  `schema`. The RFC's `validate` and `subscribe` join only if a real form
  needs them.

Lifecycle rules:

1. The host loads each runtime once per page and shares it between parameters
   that use the same runtime ID.
2. The module waits for its own custom elements (`customElements.whenDefined`).
   The host cannot know which elements a module uses.
3. If the form goes away before `mount` resolves, the host aborts `signal` and
   calls the cleanup function as soon as the promise resolves.
4. If `mount` throws or rejects, the host shows the error and the JSON
   textarea, so the user can still start a build. The browser does not check
   that JSON: the controller checks it against the schema when the build is
   submitted and answers 400 with the errors.
5. A runtime ID carries only the major version. A patch update inside
   `preact11-htm3` keeps the ID; a new major gets a new ID, and old
   Jenkinsfiles keep working on the old one.

Authoring rule (Tested): `js/scripts/check.mjs` reports `import` statements in
job code as errors. That is a dependency policy, not a security control; code
can still build any URL and load it at runtime. The checker and the controller
read runtime IDs and schema keywords from one file, `contract.json`, so they
cannot drift apart.

## 3. Trust model

Five roles meet on the form page:

| Role | Can do |
|---|---|
| Jenkinsfile author (anyone with push access) | Write the module, HTML and CSS that run in viewers' browsers |
| Viewer | Opens *Build with Parameters*; the module runs with the viewer's session and permissions |
| Admin viewer | Same as a viewer, but the session can change Jenkins itself |
| Controller | Validates values, serves modules, answers `ui.query` with the viewer's permissions |
| Approver | An admin; exists only when *Require approval of form code* is on |

A sandboxed form (section 13) changes the first row: its code runs in an
opaque-origin frame and acts as the viewer only through `ui.query`.

**Accepted risk with the default settings:** a Jenkinsfile author can run
JavaScript as any viewer, including an admin. In a multibranch job this covers branch and PR authors
with push access, because a build writes the Jenkinsfile's parameters into the
job's configuration (Assumption: the design review cites JENKINS-41929; we
have not reproduced it). Loading
the module from a separate URL does not isolate it: it still runs on the
Jenkins origin.

**Resource Root URL** (a Jenkins setting that serves workspaces, artifacts and
`userContent` from another domain): recommended, not required. It only matters
when a module loads a same-origin file that someone else controls, such as a
workspace file. A module that wants to do harm needs no such file.

**Optional approval (Tested, `FormApprovalTest`).** An admin turns it on with
`BuildUiConfiguration.requireApproval`; it is off by default. The plugin
registers a script-security `Language` named `build-ui` and asks
`ScriptApproval` about one text per form: the runtime, module, HTML and CSS,
which is everything that runs in other people's browsers. Schema and defaults
only affect the build, which the author controls anyway, so the controller
validates them and nobody approves them. Choices and the facts behind them:

- The check runs when Jenkins renders the form or panel. An unapproved form
  gets no `build-ui-root` and no `host.js`, so its code never reaches the
  content store or a browser, and the JSON textarea still submits.
- `configuring()` saves `scriptApproval.xml` on every call, and
  `getPendingScripts()` is restricted API (Fact: the access-modifier checker
  rejected it). The plugin remembers, per form, the hash of the code it last
  submitted (at most 2000 forms, in memory), so page views do not write to
  disk. Denied code therefore stays off the pending list until it changes or
  Jenkins restarts.
- The approval context key is the job's full name plus the form name.
  script-security drops an older pending entry with the same key, so the list
  holds one entry per form.
- `configuring()` approves without asking when Jenkins security is off, and
  skips the pending list for a non-admin under *Force sandbox* (Fact: read in
  the bytecode of script-security 1412 and 1436). The plugin keeps both
  behaviours to match Groovy approval. Under *Force sandbox* it does not call
  `configuring()` for a non-admin at all, and does not remember the hash:
  that call also drops the form's pending entry, and a remembered hash
  stopped a later admin view from listing the form.
- Data sources are not part of the approved text: providers check the
  viewer's permissions on every query, and an admin configures the ArgoCD
  servers.
- A sandboxed form's text starts with `// build-ui form, runtime <id>,
  sandboxed`. An admin may approve code because the sandbox limits it, so
  that approval does not cover the same code once its Jenkinsfile drops
  `sandbox: true`. The reverse holds: a sandboxed form also counts as
  approved when its page text is approved, so turning the sandbox on costs
  no second approval (Tested, `FormApprovalTest`).

## 4. CSP and module serving (Tested)

- Strict CSP is the design target: no `unsafe-eval`, and the plugin never
  loosens Jenkins's global policy (no `jenkins.security.csp.Contributor` entry
  for `unsafe-eval`).
- The controller keeps module, CSS and HTML text in `ContentStore`, keyed by
  the SHA-256 of the bytes, and `/build-ui/<sha256>.mjs|.css|.html.txt` serves
  them with `text/javascript`, `text/css` or `text/plain`,
  `Cache-Control: private, max-age=31536000, immutable`, and an Overall/Read
  check. The hash in the URL is a cache key: an unknown key gets 404.
- **Differs from the original proposal:** the check is Overall/Read, not Read
  on the job. The same key serves the build form, `input` step pages and build
  panels, so the endpoint does not know which job asked. A user with
  Overall/Read who learns a hash can fetch that text. The hash appears only on
  pages of jobs the user can read.
- The store holds the 2000 most recently used entries in memory. Definitions
  register their text each time a page renders, so a restart refills it.
- CSS reaches the page through a constructed stylesheet (`adoptedStyleSheets`)
  inside the form's shadow root, never as an injected `<style>` tag. HTML goes
  in with `innerHTML`; enforced CSP drops inline event handlers in it without
  an error.
- The checker reports `on*=` attributes and `<script>` in `html`.
- Enforced mode is tested on a real Jenkins. Report-only mode, the default on
  a production Jenkins, is tested on a real Jenkins for the eval runtimes
  (section 12); it is the looser case.

## 5. Validation (Tested)

- `UiParameterDefinition` extends `SimpleParameterDefinition`, so the
  Pipeline `build` step converts string values through `createValue(String)`
  and validates them.
- One JSON Schema check runs on the controller for every path that creates a
  value: the form and `buildWithParameters` answer 400 with the reason, the
  `build` step fails with it, and `QueueGuard` (a `QueueDecisionHandler`)
  refuses to queue a hand-built invalid `ParametersAction`.
- `x-git-ref` is checked before the build starts, never under the queue
  lock. `Queue.schedule2` calls every `QueueDecisionHandler` with the lock
  for the whole queue held (Fact: jenkins-core 2.541.1 bytecode), and listing
  refs is a network call with a 10-minute transport timeout, so one slow Git
  server stopped every job from scheduling. Request paths (form,
  `buildWithParameters`) check refs on the request thread and answer 400.
  For every queued build, `RefGuard` (a `QueueTaskDispatcher`) keeps the
  item blocked while a pool thread lists the refs, then lets it run or
  cancels it. A cancelled build never starts and takes no build number; the
  reason is in the controller log, and a waiting `build` step fails.
- A `formFrom` schema lives in the repository, so it follows the same rule
  (section 14): request paths read it on the request thread, `QueueGuard`
  checks only that the value is JSON, and `RefGuard` validates the value
  against `schema.json` on a pool thread.
- Limits: 256 KiB per document, nesting depth 32, 1000 array items, 1000
  object properties, no duplicate keys, patterns up to 256 characters. All
  patterns of one document share a 50 ms budget: a per-string budget let a
  value of thousands of strings cost minutes of controller CPU. Numbers parse
  as exact decimals (a double turned `1e400` into Infinity), and `enum`,
  `const` and `uniqueItems` compare them by value. No `$ref`; any keyword
  outside the subset fails when the job is configured.
- `schema` and `defaultJson` must parse when the job is configured. The
  default is checked against the schema when a build uses it, and by the form
  check on the freestyle configuration page; `QueueGuard` refuses a build
  that would use an invalid default and logs the reason. The default getter
  itself does not throw, because the REST API exports it and a throwing
  getter dropped the parameter from `job/<name>/api/json`.
- The form checks (`checkSchema`, `checkDefaultJson`) need Item/Configure,
  or Overall/Administer outside a job: the caller supplies both the schema
  and the value, so each check costs controller CPU.
- The runtime ID is checked on every path that sets it: the setter, and
  `readResolve` for `config.xml` (REST, CLI) and `build.xml`, which bypass
  the setter. An unknown ID becomes `preact11-htm3` with a warning in the
  log. `host.js` builds the import URL from the ID, so an unchecked ID could
  load any same-origin script, and a form without code needs no approval.
- `params.BUILD_CONFIG` is a compact JSON string. Values over 32 KiB are not
  exported as an environment variable.
- A 32 KiB value in the `buildWithParameters` query string did not start a
  build; in the POST body it did (Measured). The README says to use the body.

## 6. Measurements

Jenkins 2.541.1 in development mode (`mvn hpi:run`) enforces this policy on
its pages (Measured, 2026-10-23):

```
base-uri 'none'; default-src 'self'; form-action 'self'; frame-ancestors 'self';
img-src 'self' data:; script-src 'report-sample' 'self' usage.jenkins.io;
style-src 'report-sample' 'self' 'unsafe-inline'; report-to …; report-uri …
```

The lab replays it with `LAB_CSP` and adds the hash of its own import map to
`script-src`. The table below holds under both this policy and the lab's
stricter one (`style-src 'self'`).

| Result | Libraries |
|---|---|
| Pass | vanilla JS, vanilla custom element, Preact 11.0.1 + htm 3.1.1 (hooks and signals), lit-html 3.3.3, LitElement, Solid 2.0.0-rc.15 + `@solidjs/html`, uhtml 5.0.9, Arrow 1.0.6, VanJS 1.6.1, Mithril 2.3.8, `@alpinejs/csp` 3.17.4 |
| Fail with `EvalError` | `solid-js/html` 1.9.17, Vue 3.5.44 runtime compiler, petite-vue 0.4.1, Alpine 3.17.4 |

Versions are the ones in `js/pnpm-lock.yaml`, and `js/lab/run3.mjs` reproduces
every row except `solid-js/html` 1.9.17. That row was measured outside the lab:
the lab has no Solid 1.x page or dependency. An earlier draft of this table
recorded Solid 2.0.0-rc.17 and Alpine 3.17.5; the lab pins rc.15 and 3.17.4, and
no run on the newer versions is recorded.

Bundled with esbuild, minified, gzip: lit-html 3.5 KB, Preact + htm 6.5 KB,
Preact + htm + signals 8.9 KB, Solid 2.0 RC + html 20.2 KB.

Other measured behaviour:

- Enforced CSP makes `new Function()` throw. Report-only mode lets it run and
  sends one violation report per page load.
- Under Jenkins's policy, a script-created `<style>` and `setAttribute('style', …)`
  work, because `style-src` allows `'unsafe-inline'`; inline `onclick` stays
  blocked. Under the lab's stricter `style-src 'self'` both style paths are
  blocked too. `<link rel=stylesheet>`, `adoptedStyleSheets` and CSSOM property
  writes work under both, so the plugin uses those and does not depend on
  `'unsafe-inline'`.
- Jenkins's policy has no `connect-src`, so `default-src 'self'` applies to
  `fetch`.
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
- Jenkins 2.580.1 sends the same CSP header as 2.541.1.
- Jenkins answers the *Build with Parameters* page itself with HTTP 405 on
  purpose, so scripts do not mistake the form for a triggered build; the
  browser logs it as a failed resource.
- Jenkins's `.jenkins-input` class sets `display`, which overrides the
  `hidden` attribute; the host hides the fallback textarea with
  `style.display` too.
- Java 21's regex engine runs `^(a+)+$`, `^(a|aa)+$` and `^(\w+\s?)*$` against
  their classic attack strings in under 1 ms, but `^(.*?,){14}P` against a
  30-field CSV line took 9.7 s and `^(a+)+\1$` took 1.3 s. The 50 ms budget
  stops the first in the test suite.
- Runtime bundles, minified and gzipped: `preact11-htm3` 6.8 KB,
  `alpine3-csp` 23.3 KB, `alpine3` 19.4 KB, `vue3` 65.6 KB.

Facts behind the measurements: Jenkins enforces UI CSP only when an admin
turns it on, since 2.539
([CSP](https://www.jenkins.io/doc/book/security/csp/)). htm's last release is
3.1.1 from October 2022. `@jsonforms/vanilla-renderers` 3.8.0 requires React.

## 7. Solid 2.0 comparison (Measured)

Preact 11.0.1 + htm 3.1.1 and Solid 2.0.0-rc.15 + `@solidjs/html` built the
same form (`js/lab/pages/cmp-*.mjs`, run with `js/lab/run-compare.mjs` under
Jenkins's CSP, 5 runs, same result each time). The form has a conditional
nested section, server data arriving after 100 ms, a keyed list with add and
remove, an interval cleaned up on unmount, and `<x-chips>`, a small custom
element of our own with array properties `options` and `value` and a
`chips-change` event, defined 300 ms after the page loads.

| Mode | Preact | Solid 2 |
|---|---|---|
| Plain element, form renders at once | fails: chips never show, value stays `["api"]` | fails the same way |
| Plain element, module waits for `customElements.whenDefined` | **passes** | fails the same way |
| Element that re-applies properties set before its upgrade (as Lit-based elements do), renders at once | fails | **passes** |
| Conditional section, nested state, list add/remove, final JSON | passes in every mode | passes in every mode |
| Cleanup (DOM emptied, interval cleared) | passes | passes |
| CSP violations | none | none |
| App + runtime, minified, gzip | 7.2 KB | 30.4 KB |
| App code, non-comment lines | 33 | 39 |

Why the custom element results differ:

- Preact sets a property only if the element already has it (`name in dom`).
  On an element not yet defined it writes an attribute, so the array becomes
  the string `"api"` and the element never sees it. Waiting for the
  definition fixes it, which is the module's job under the contract.
- Solid 2's `html` sets `prop:` bindings on the element before it joins the
  page. Even with the element defined first, `options` and `value` land as
  own properties of the instance and hide the class's setters. Waiting does
  not help; only an element that re-applies such properties in its
  constructor works.
- Preact 11 runs `useEffect` cleanups shortly after `render(null, root)`
  returns, not inside it (12 of 12 runs). A probe that checked the timer
  right after unmount mistook this for a leak 1 to 5 times in 10.

Conclusion: `preact11-htm3` stays the default. Solid 2 is four times larger
and breaks plain custom elements in a way a module cannot work around by
waiting, so the comparison gives no reason to ship `solid2-html`. Revisit
when Solid 2.0 is stable and its `html` sets properties after insertion, or
when a real form needs fine-grained updates that Preact cannot keep fast.

## 8. Assumptions

- The plugin behaves the same in Firefox and Safari as in Chromium.
- In a multibranch job, a branch's Jenkinsfile writes its parameter
  definitions into that branch job (section 3).

## 9. Phase 0 acceptance criteria

Phase 0 passes when a minimal plugin on Jenkins 2.541.x and 2.580.x shows the
items below. Result on 2026-10-23: all five passed on 2.541.1 and 2.580.1 (32
JenkinsRule tests, 8 browser tests).

1. The real Jenkins CSP header, copied into the lab, and every lab result
   re-run against it. Passed: section 6.
2. A module and its CSS served under enforced CSP, with the right MIME types,
   content-hash URLs and a permission check. Passed: `UiParameterTest` and
   the browser suite, which fails on any CSP violation.
3. `preact11-htm3` mounting, unmounting, and handling a `mount` that rejects or
   outlives its form. Passed: browser tests for both cases.
4. The JSON value reaching `params.BUILD_CONFIG`, validated, from the form,
   `buildWithParameters` and the Pipeline `build` step; invalid values
   rejected on each path. Passed: `UiParameterTest` and the browser suite.
5. The reference form in about 60 Jenkinsfile lines. Passed:
   `examples/reference.Jenkinsfile` has 53 lines.

## 10. Not built yet

- Sandboxed forms in Firefox and WebKit: the sandbox tests have run only in
  Chromium (on 2.541.1 and 2.580.1).
- Exfiltration from a sandboxed frame through channels Jenkins's CSP does
  not cover (WebRTC, DNS prefetch): not tested, not claimed.
- Live theme changes in a sandboxed frame: the host copies the theme once,
  when the frame loads.
- A browser test of approval: `hpi:run` runs without security, where
  script-security approves everything, so `FormApprovalTest` covers approval
  through JenkinsRule pages instead.
- A release: the workflow and `CHANGELOG.md` exist, and a local rehearsal of
  its steps built `build-ui.hpi` 0.1.0. No tag exists yet; README » Release
  lists the steps before the first one.
- Firefox and WebKit runs: CI runs them on the current LTS line, but this
  container has only Chromium, so no Firefox or WebKit run has happened yet.
- `formFrom` with an SCM other than Git, or with a remote that needs
  credentials: the tests use local Git repositories without credentials
  (on 2.541.1 and 2.580.1; section 14).
- The Git Parameter comparison and the integration tests on 2.580.1, and
  over HTTP or SSH remotes: they ran on 2.541.1 with `file:` repositories
  only (section 16).
- `uiPanel` keeps its old `html` rule: a `<script>` there is a checker error,
  and the controller does not split a panel's `html`.

## 11. Compatibility and upgrades (Policy)

The plugin follows semantic versioning; a release is a `vX.Y.Z` tag.
What each part promises:

| Part | Within a major version | A breaking change |
|---|---|---|
| Module contract `mount({ root, ui, runtime, signal })` | New `ui` fields and new `runtime` exports only | A new runtime ID; the old ID keeps the old contract |
| Runtime IDs | `preact11-htm3` takes Preact 11.x and htm 3.x patch and minor releases in place | Preact 12 ships as `preact12-htm3`; the old ID stays for at least a year, and the checker warns about it once it is deprecated |
| Schema keyword subset | Keywords are added, never removed or narrowed | Removing one is a major release |
| Data provider answers | Fields are added, never renamed or removed | A new provider ID |
| Jenkinsfile fields of `uiParameter` and `uiPanel` | New optional fields only | A major release with a migration note |

Baseline: the minimum Jenkins version rises when the update center stops
serving plugins for it (about a year back), or when a needed API is newer.
Java follows the baseline. CI always tests the minimum line and the current
LTS line. Every raise goes in `CHANGELOG.md`.

Size budgets (gzip): host 4 KB, sandbox boot script 2 KB, `preact11-htm3`
8 KB, `schema-form` 9 KB, `alpine3-csp` 25 KB, `alpine3` 21 KB, `vue3` 68 KB.
`pnpm vendor:check` fails past a budget, so growth past it is a decision
taken in the commit that raises it. Today: 3.9, 1.6, 6.7, 7.5, 23.3, 19.4 and 65.6 KB. The host
budget went from 3 to 4 KB with the sandbox, whose frame setup and message
handling added 1 KB to `host.js`.

## 12. Alpine and Vue runtimes (Tested)

Three runtimes, bundled from `js/runtimes/<id>.mjs` like the others:

| ID | Library | `runtime` holds | Needs eval |
|---|---|---|---|
| `alpine3-csp` | `@alpinejs/csp` 3.17.4 | `Alpine`, `start(root)` | no |
| `alpine3` | `alpinejs` 3.17.4 | `Alpine`, `start(root)` | yes |
| `vue3` | Vue 3.5.44, `vue.esm-browser.prod.js` (template compiler included) | Vue's whole API | yes |

`contract.json` lists the eval runtimes under `evalRuntimes`. `Runtimes`
reads it on the controller and `check.mjs` reads it in the checker, which
warns when a Jenkinsfile uses one.

### Mechanism

- `BuildUiConfiguration.allowEvalRuntimes` is off by default and settable
  with JCasC (`unclassified.buildUi.allowEvalRuntimes`). Its description
  says these runtimes stop working the day Jenkins enforces CSP.
- Setting off: the form or panel view renders a notice and the JSON field,
  with no `build-ui-root` and no `host.js`, as `FormApproval` does. The code
  never reaches the content store and the browser never loads the runtime.
  The view checks the setting before approval, so blocked code does not
  reach the pending list. Allowed code still needs approval when approval is
  on.
- Setting on: the view marks the root `data-eval="true"`. Before it loads
  such a runtime, `host.js` calls `new Function('')` once per page. If that
  throws, it shows "This form's runtime vue3 needs eval, which this Jenkins's
  Content Security Policy blocks." with the JSON field, and skips the
  download.
- The browser check is the source of truth because a reverse proxy can add
  its own policy, which the controller cannot see.
- Alpine runs only inside the form: the runtimes replace `Alpine.start()`
  with a function that throws, and `start(root)` calls
  `Alpine.initTree(root)` and returns `() => Alpine.destroyTree(root)`. Vue
  forms call `createApp(...).mount(root)` and return `app.unmount`.

### Measured facts

Chromium (Playwright 1.56.1), 2026-10-23, under Jenkins 2.541.1's policy.
Alpine lifecycle from `js/lab/alpine-shadow-probe.mjs` (`@alpinejs/csp`
3.17.4 as it ships); eval behaviour from `js/lab/eval-runtime-probe.mjs`
(the shipped bundles); the browser suite repeats the main ones on a real
Jenkins.

- `Alpine.initTree(root)` without `start()` runs `x-data`, `init()`,
  `x-model`, `x-text`, `x-show`, `x-for` and `x-if` inside a shadow root.
  Markup on the page outside the form stays untouched.
- `Alpine.start()` initialised `x-data` markup on the page outside any
  form, and markup added to the page later, because it observes `document`.
  It did not initialise a form waiting in a shadow root. On a Jenkins page
  it can only do harm.
- Two forms with their own component names keep separate state.
- Two forms that register the same `Alpine.data` name: each top-level
  component kept its own data, but a component created later inside the
  first form (by `x-if`) got the second form's definition. Names are global
  to the page; the README tells authors to name components per form.
- `Alpine.destroyTree(root)` calls `destroy()`, stops `$watch` callbacks and
  `x-text` updates, and detaches `x-model`. Removing a form's DOM without it
  calls no `destroy()`, and its watchers keep running.
- Importing either Alpine bundle sets
  `Element.prototype._x_toggleAndCascadeWithTransitions` (read in the
  bundle).
- Enforced CSP: `new Function('')` throws `EvalError` and fires a
  `securitypolicyviolation` event, with no console message of its own.
  `alpine3`'s `start(root)` returns normally, leaves the form unrendered,
  logs "Alpine Expression Error" warnings and throws uncaught errors later.
  `vue3`'s `mount` throws `EvalError` and leaves the root empty.
- Report-only CSP: `alpine3` and `vue3` render, and the page fires one
  `securitypolicyviolation` event per eval with disposition `report`.
  Chromium prints nothing to the console for them.
- Jenkins 2.541.1 picks the CSP header through `CspHeaderDecider`
  extensions, read per request (Fact: bytecode of `jenkins-core` 2.541.1).
  The highest ordinal reads the system property
  `jenkins.security.csp.CspHeader.headerName`; set to
  `Content-Security-Policy-Report-Only` it overrides development mode, which
  enforces CSP under `hpi:run` and JenkinsRule. The browser suite sets and
  clears it through the script console, so one Jenkins tests both modes.

### Limits

- `alpine3` and `vue3` break for every user on the day an admin enforces
  CSP. Their forms then fall back to the JSON field.
- In report-only mode the browser reports each eval as a violation to the
  `report-uri` Jenkins names (Assumption: we saw the events, not the
  requests). Jenkins's log at default levels showed nothing for them.
- `alpine3-csp` expressions are property paths and simple calls; logic
  belongs in `Alpine.data` components.
- `x-teleport` finds its target with `document.querySelector` (read in the
  bundle), so it cannot target an element inside the form. Not tested.

## 13. Sandboxed forms (Tested)

`SandboxTest` (JenkinsRule) covers the endpoints; `js/e2e/sandbox.spec.mjs`
(Chromium, Jenkins 2.541.1 and 2.580.1) covers the browser side.

### Mechanism

1. The view marks the root `data-sandbox="true"` when the form asks for it
   or `sandboxAll` is on, and the form has a module, HTML or CSS. A
   `schema-form` without HTML or CSS runs only the plugin's code and stays
   on the page. Approval (section 3) decides first whether the root exists.
2. `host.js` fetches the HTML and CSS as before and adds
   `<iframe sandbox="allow-scripts" title="<form name>">` to the shadow root.
   Its `src` is `/build-ui/frame?runtime=<id>&module=<key>`, a page of
   `BuildUiAction` with the same Overall/Read check as the content endpoint.
3. The frame endpoint checks that the runtime exists and the module key is
   in `ContentStore`, mints a token and answers a document with no form code:
   a boot script tag and the module's one-time URL in `data-module`. It sends
   `Cache-Control: no-store`, so the browser never reuses a spent token.
4. `SandboxAction` (`/build-ui-sandbox/`, an `UnprotectedRootAction`) serves,
   with `Access-Control-Allow-Origin: *`, the boot script and the runtime
   bundles from a fixed list (the request path is only a key into that list)
   and `module/<token>.mjs`.
5. Tokens (`SandboxTokens`): 128 random bits from `SecureRandom`, bound to one
   content key, used up by the first request, valid for 60 s, at most
   10 000 held (the oldest goes first). The content key itself never reaches
   the frame.
6. On the iframe's `load` event the host posts one message,
   `build-ui:init`, with the name, job, schema, current value, HTML, CSS and
   theme, and transfers a `MessagePort`. The host never listens to `window`
   messages; it reads only that port.
7. The frame sends `setValue` (a JSON string; the host writes the textarea),
   `query` (the host runs the same `ui.query` fetch with the viewer's session
   and answers on the port), `height` (from a `ResizeObserver`; the host sets
   the iframe height, at most 10 000 px), `mounted` and `error`. An error, or
   no `mounted` within 30 s, shows the usual error and JSON textarea.
8. The theme is every custom property computed on the form's root element
   plus its `color`, `font-family`, `font-size`, `line-height` and
   `color-scheme`; the frame sets them on its `<html>`.

### Measured (Chromium, Playwright 1.56.1, Jenkins 2.541.1, 2026-10-23)

From `js/lab/sandbox-probe.mjs`, with Jenkins's CSP header on every response:
inside `<iframe sandbox="allow-scripts">` the origin is `"null"`; a
same-origin classic `<script src>` runs (`'self'` still matches the URL);
module scripts load in CORS mode and need `Access-Control-Allow-Origin`;
inline scripts, `eval` and `blob:` imports are blocked; the frame document
request carries the viewer's cookies, and the frame's own requests carry
none.

From the browser suite against Jenkins:

- Jenkins sends its CSP header on the frame endpoint's response too.
- In the frame, `self.origin` is `"null"`; reading `parent.document` and
  `document.cookie` throws `SecurityError`; `fetch` of `/jenkins/api/json`
  fails with `TypeError` (no CORS header); setting `top.location` throws
  `SecurityError`; a message posted to the page window changes nothing.
- `fetch` and an image to another host fail; Playwright reports the image as
  a request that failed with `csp`. Navigating the frame to another host
  sends no request, which matches the page's `default-src 'self'` acting as
  `frame-src`.
- A host element with `display: block` let its children's margins collapse
  through it, so its height was smaller than the document and the frame
  scrolled. `display: flow-root` keeps the margins inside.
- axe-core runs its page-level rules (`landmark-one-main`,
  `page-has-heading-one`, `region`) on the frame's document. The test turns
  those three off for the frame, which is part of the Jenkins page, and
  checks with an unlabelled input that axe does look inside the frame.
- Stapler answers 500 (`IllegalArgumentException` from `TokenList`) for a
  path with a decoded `..` segment such as `%2e%2e`, before any plugin code
  runs. `SandboxTest` accepts any refusal for traversal paths and checks
  that no content leaks.

### Limits

- The code can draw misleading content inside its own frame.
- Through `ui.query` the code reads whatever the viewer can read, and it can
  put that into the submitted value, which reaches a build the Jenkinsfile
  author controls.
- Channels outside CSP, such as WebRTC, are not tested.
- Firefox and Safari are not tested; the host's theme copy relies on the
  computed style listing custom properties, which we have seen only in
  Chromium.
- Every frame costs a request to the protected endpoint and one per script.

## 14. Forms from the repository and single-file forms (Tested)

`FormFromTest` (JenkinsRule, 16 tests), `SingleFileHtmlTest` (15 plain unit
tests), eleven checker tests and `js/e2e/form-from.spec.mjs` (Chromium) cover
this section, all on Jenkins 2.541.1 with local Git repositories.

The problem, in the owner's words: "I changed the HTML in the Jenkinsfile
and pushed to git. The UI does not come up with the new version right
away." Jenkins keeps a job's parameter definitions in its configuration, and
only a build that runs the new Jenkinsfile replaces them (README, "When a
changed form shows up"). The owner chose to read the form's files from the
branch head when *Build with Parameters* opens.

### Mechanism

1. `uiParameter(formFrom: 'forms/deploy')` names a folder relative to the
   repository root. The setter checks the path: at most 200 characters,
   segments of `[A-Za-z0-9._-]` joined by `/`, no `.` or `..` segment, no
   leading or trailing `/`, no `\`. Setting `module`, `html`, `css`,
   `schema` or `defaultJson` as well fails the definition, in either order.
   `config.xml` bypasses the setters, so `readResolve` keeps a bad value as
   an error that the page and every value check report.
2. `ScmFormReader`, an optional extension that loads with Pipeline and the
   SCM API, opens an `SCMFileSystem` on the controller. For a multibranch
   branch job, found through `SCMSource.SourceByItem` and
   `SCMHead.HeadByItem` (no compile dependency on branch-api), it reads the
   revision Pipeline reads the Jenkinsfile from: `source.fetch(head)` for
   the tip, `source.getTrustedRevision(tip)`, then
   `SCMFileSystem.of(source, head, revision)` (Fact: workflow-multibranch
   841's `SCMBinder` and `readTrusted` make the same three calls, read in
   the bytecode). A change request from an author the source does not
   trust, such as a fork's pull request, therefore gets all five files from
   the trusted revision, usually the target branch, just as its build runs
   the target branch's Jenkinsfile. Its own `form.mjs` never reaches a
   viewer and its `default.json` never sets the parameter of the build its
   webhook starts. A job whose definition is `CpsScmFlowDefinition` reads
   through `SCMFileSystem.of(job, scm)`. Any other job, and the `input`
   step, gets an error naming what is supported. The job's own credentials
   stay on the controller; the browser sends nothing that picks the
   repository, branch or path.
3. `FormFrom` caches one result per job and folder for 10 seconds, at most
   256 entries, failures included, so a broken folder does not cost a fetch
   per page view. Concurrent requests share one read in progress. A read
   runs on a pool thread under `ACL.SYSTEM2` and a caller waits at most 20
   seconds; a read that takes longer keeps going and fills the cache for
   the next request. The browser sees the reader's own reasons and what is
   wrong with the files; any other failure, an `IllegalArgumentException`
   from SCM code included, shows only the job, the path and the exception's
   class, because such messages can quote a remote URL with its token. Files are capped at 512 KiB (`form.mjs`, `form.html`,
   `form.css`) and 256 KiB (`schema.json`, `default.json`, then the JSON
   limits of section 5). An `ItemListener` drops a job's entries when it is
   renamed, moved or deleted: without it, a job that took a renamed job's
   name within 10 seconds showed the other job's form (found by
   `IntegrationTest`, 2026-10-27). A branch job is one head, and the
   reader decides per read which revision that head may use, so an entry
   read at the trusted revision for an untrusted change request never
   serves another job, and a trusted head's entry never serves an
   untrusted one.
4. The brief asked for a cache per job and resolved head revision. The key
   is the job and folder instead, and the entry records the revision for the
   log: `SCMFileSystem` reports a revision only after `of()` has resolved the
   head (Fact: the API's signatures in scm-api 728), so knowing the revision
   costs the same open as reading the files.
5. A page reads the form once per request (a request attribute), so the
   module, HTML, CSS and schema on one page come from one read. The text
   then goes into `ContentStore` like inline fields: content-hash URLs,
   approval, the eval gate and the sandbox work unchanged, and pushed code
   waits for approval when approval is on.
6. Values: the form and `buildWithParameters` validate on the request
   thread against `schema.json` from the same cache. `QueueGuard` runs with
   the queue lock held, so for a `formFrom` value it checks only that it is
   JSON; `RefGuard` (section 5) holds the item and validates it against
   `schema.json` on a pool thread, then lets it run or cancels it.
7. Defaults come from `default.json`. Without a request (timers, the `build`
   step, `params` in a running build) the definition finds its job by
   scanning jobs once for the property that holds it, then reads on the
   caller's thread with the 20-second bound. When the folder cannot be read,
   the getter throws, so the build that wanted a default fails with the
   reason instead of starting with another value.

Single-file form (`SingleFileHtml`): before anything reaches `ContentStore`,
the controller splits `html` or `form.html`. The content of one
`<script type="module">` becomes the module, every `<style>` joins the CSS
after `css` or `form.css`, and the rest stays HTML. The scan follows HTML's
rule that a script ends at the first `</script` and a style at the first
`</style`, and reads the rest as the HTML tokenizer does: a comment also ends at `<!-->`, `<!--->` and `--!>`; only
`<` before an ASCII letter starts a tag; a quoted value may hold `>`; `/`
and the end of a quoted value separate attributes; only tab, line feed,
form feed, carriage return and space count as space. The HTML left after
the split, which is what the browser gets, is then checked for `on*=`
attributes on start tags, the checker's rule. `textarea`, `title`,
`noscript`, `iframe`, `noembed`, `noframes` and `xmp` hold text in HTML
but markup inside SVG or MathML, so the check reads their content as
markup and refuses a tag or comment that contains their end tag, where the
two readings part. A `<` right before a script or style is refused too:
taking the element out would join it to the text after it. A review found
the earlier scan let `<!--><img onerror=...>-->` and `<img/onerror=...>`
through (2026-10-27). Tested once by hand, not in a suite: set as
`innerHTML` in Chromium from Playwright 1.56.1, every input the tests refuse
for a handler gave an element a live `on*` attribute, and the inputs they
allow gave none. It refuses a classic script, any `src`, two module
scripts, a module script next to `module` or `form.mjs`, an inline handler
and an unclosed element. Every scan moves forward, and each text element's
end tag is searched for once, so the time is linear in the text;
`SingleFileHtmlTest` and the checker test run inputs that make a
backtracking regex rescan from every `<`. Reason for the split, a design choice and not
measured here: no browser ships HTML modules, and Jenkins's CSP blocks
inline scripts, so the module must come from its own URL.

### Measured

JenkinsRule on Jenkins 2.541.1, Git plugin 5.10.1, scm-api 728, git 2.43.0,
2026-10-27:

- `GitSCMFileSystem` reads a `file:` repository with the branch spec
  `*/main` once `hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` is
  set, for both job types.
- A read after the controller's Git cache exists took 57 ms; a read from the
  plugin's cache took 17 µs (one run, `FormFromTest`).
- A Pipeline that reads `params` asks every parameter defined on the job
  for its default: `ParamsVariable.getValue` called
  `getDefaultParameterValue` in the build that defined the parameter. An
  inline Pipeline with `formFrom` therefore fails its defining build with
  "… is neither", which tells the author at once.
- With the repository on a server that accepts connections and never
  answers, a `build` step with `wait: false` returned in under 10 seconds,
  another job built meanwhile, and the queued item left the queue without
  starting once the server closed.
- Browser suite (Chromium, Playwright 1.56.1, `hpi:run`, enforced CSP): after
  a commit to `form.html`, reloading *Build with Parameters* every 2 seconds
  showed the new markup with no build in between; the whole test, setup
  included, took between 12.7 s and 18.2 s in three runs. The example's single
  `form.html` rendered with its `<style>` applied and no CSP violation.
- In jenkins-core 2.541.1 the classes that call `getDefaultParameterValue`
  are the parameter definitions, `hudson.cli.BuildCommand` and
  `jenkins.model.ParameterizedJobMixIn`, which calls it before it queues
  (Fact: bytecode search). No queue class calls it.

### Limits

- Supported jobs: multibranch branch jobs and *Pipeline script from SCM*.
  Inline Pipeline scripts, freestyle jobs and the `input` step show the
  error and the JSON field, and refuse values.
- A push shows up when the 10-second entry expires, not at once.
- A read happens on the request thread for page views and form submits,
  and on the caller's thread for defaults without a request, each bounded
  by 20 seconds. A `build` step that needs a default blocks its Pipeline's
  CPS thread for at most that long.
- `job/<name>/api/json` exports the default. If the folder cannot be read,
  the getter throws and that export drops the parameter (section 5 measured
  that a throwing getter drops it).
- A `copyWithDefaultValue` copy keeps `formFrom` and stores the given value
  as its default. Such a copy is not meant to be saved; `readResolve` would
  report it as a conflict.
- Only Git was tried, only `file:` repositories without credentials, only
  Chromium. Trust was tested with scm-api's `MockSCMSource` (a trust-aware
  repository, one untrusted and one trusted change request) and a file
  system written for that test, not with GitHub, GitLab or Bitbucket. In
  that test the untrusted change request's page showed the target branch's
  module, its first build took the target branch's `default.json`, and the
  trusted change request read its own tip.
- A change to whom the source trusts shows up when the 10-second entry
  expires, as a push does.

## 15. Local preview and module types (Tested)

The RFC asks for local preview and editor checking. `js/test/preview.test.mjs`
(10 Node tests), `js/preview/preview.spec.mjs` (3 Chromium tests, no Jenkins)
and `js/test/typecheck.test.mjs` (4 Node tests) cover this section.

### Mechanism

- `js/preview/server.mjs` serves the page, the content store, the query
  endpoint, the sandbox frame and the plugin's files under the paths Jenkins
  uses, so `host.js` and `sandbox/boot.js` run unchanged. Content keys are the
  SHA-256 of the text, as in `ContentStore`.
- Each page response carries the header from section 6 plus
  `report-uri /_preview/csp-report`; the server prints each report.
- It reads Jenkinsfiles and `formFrom` folders through the checker's exported
  functions (`tokenize`, `findCalls`, `splitHtml`, `readFormFolder`,
  `parseJson`, `checkSchema`), and joins the parts as `FormCode.of` does. The
  checker and the preview keep one copy of each rule.
- `js/preview/schema.mjs` ports `Schema.validate` with the same keyword
  order, JSON Pointers and messages. The JSON limits come from the checker's
  `parseJson`. Differences: JavaScript regular expressions with no time
  budget, doubles instead of exact decimals.
- The page script outside the forms polls each value textarea every 200 ms,
  because `host.js` sets `textarea.value`, which fires no event, and asks the
  server for the verdict. Reloads arrive over server-sent events from
  `fs.watch` on the folders of the watched files: editors that replace a file
  on save end a watch on the file itself.
- The server listens on loopback only and answers 403 to a Host header other
  than `127.0.0.1`, `localhost` or `[::1]` with its port. A loopback bind alone
  does not stop DNS rebinding.
- `js/types/build-ui.d.ts` imports nothing, so it works when copied into
  another repository. `MountFor<id, Value>` maps a runtime id to its
  `runtime` type. `ui.query` has one overload per shipped provider, and the
  catch-all overload refuses those ids, so their arguments stay checked.
- `pnpm typecheck` runs the TypeScript 6.0.3 compiler API with `checkJs` and
  `strict: false`. TypeScript 6 defaults to strict; with it all four example
  modules failed (implicit `any` parameters, `useState([])` typed `never[]`).

### Measured (2026-10-27, Node 24.22.1, Chromium from Playwright 1.56.1)

- The three preview browser tests took 3.1 s together; the ten Node tests
  1.5 s.
- `pnpm typecheck` on one module took 2.2 s and 2.8 s in two runs, almost all
  of it loading the compiler and `lib.dom.d.ts`.
- Under the preview's CSP, `new Function()` in a module threw, the page
  listed `script-src blocked eval`, and the server logged the report.

### Limits

- The preview loads no Jenkins stylesheet and shows `uiParameter` forms only;
  `ui.job` is `preview`.
- The value check does not run `x-git-ref`, and its `pattern` can disagree
  with Java's on syntax that only one of them accepts.
- No test compares the preview's split with `SingleFileHtml` on the same
  input. Both follow the rules in section 14, and the checker tests pin the
  JavaScript side.
- Only Chromium ran the browser tests.

## 16. gitRefs, Git Parameter parity and other plugins (Tested)

`GitRefsProviderTest` (21 tests), `GitParameterParityTest` (5) and
`IntegrationTest` (4), JenkinsRule on Jenkins 2.541.1 with local Git
repositories, cover this section.

The RFC lists what Git Parameter does that `gitRefs` must match before it
can replace it: tagFilter, DESCENDING_SMART, repository matching,
validation of the selected ref at submission, and bounded, paged or
searchable results. The reference is Git Parameter 462.463 (the BOM's
version), read in its source and run in the test.

### Decisions

- `repository` is a glob of the remote URL, matched against the URL the job
  configures without its user and password part: `https://user:token@host/x`
  is matched, answered and named in errors as `https://host/x`, because
  anyone with Build permission may query and a glob would otherwise probe
  the token. scp-like `git@host:x` has no password and stays as it is. Git Parameter's `useRepository` matches remote URLs too, as a
  regex with `find()` (Fact: `SCMFactory` and `GitParameterDefinition` in
  462.463); it has no name or ID match, so neither does `gitRefs`. A glob
  instead of a regex keeps the browser's input out of the regex engine. The
  argument filters the job's own remotes and is never contacted, so it opens
  no SSRF path; a glob that matches none answers 400.
- `search` is a case-insensitive substring of the name, applied after the
  globs, at most 100 characters. Plain text cannot backtrack, and a person
  typing into a search box expects a substring, not a pattern.
- Paging: `offset` next to the existing `limit`, both per list, and a new
  `total` object with the count of each list after filtering and before
  paging. `branches` and `tags` keep their shape, so existing forms see
  only an added field, as section 11 allows for provider answers.
- `x-git-repository` next to `x-git-ref` restricts the submit and queue
  check to the matching remotes. The schema check refuses it without
  `x-git-ref`, or when it is not a string of 1 to 100 characters.
- Where Git Parameter behaves differently on purpose, `gitRefs` keeps its
  own behaviour and the parity test asserts the difference (below).

### Mechanism

1. `GitRefsProvider.refs(job, repository)` collects the `UserRemoteConfig`s
   of the job's `GitSCM`s, keeps those whose URL the glob matches, lists
   each remote's refs through the 30-second cache (key: job, URL,
   credentials ID), and drops peeled `^{}` entries.
2. `query` drops names that do not contain `search`, applies the branch and
   tag globs, sorts, counts, then cuts `offset` and `limit` from each list.
3. `UiParameterDefinition.checkRefs` reads `x-git-repository` from the same
   schema node as `x-git-ref` and passes it to `RefLookup.hasRef`, on the
   request thread for the form and `buildWithParameters` and in `RefGuard`
   for queued builds (section 5).
4. `Globs` matches without `java.util.regex`. It walks the glob and keeps
   only the last `*` to retry from, so a match costs at most glob length
   times name length steps.

### Measured

JenkinsRule, Jenkins 2.541.1, Git Parameter 462.463, Git plugin 5.10.1, git
2.43.0, Java 21, 2026-10-27:

- The old glob code compiled `*a*a*a*a*b` to `.*a.*a.*a.*a.*b`; matching it
  against 200 `a`s took 20.5 s, and six stars ran past 100 s. Anyone with
  Build permission writes the filter and anyone with push access writes ref
  names. The new matcher fails `*a` repeated 49 times plus `*b` against
  10 000 `a`s within the test's 2-second bound.
- Git Parameter's `tagFilter: 'v*'` returned `old/v0.9` as well: it passes
  the filter to `git ls-remote`, which matches the end of the ref path.
  `tags: 'v*'` matches whole names and leaves it out; `tags: '*/v*'`
  returns it.
- `DESCENDING_SMART` returned `v2.0, v1.10.0-rc1, v1.10.0, v1.9.0, v1.1,
  v1.0`; `version-desc` returned the same list with the release before its
  candidate. Its comparator ranks a name after its own prefix, and the
  descending sort reverses that.
- Neither plugin listed a peeled `^{}` entry for the annotated tag.
- Without `useRepository`, Git Parameter listed the job's first repository
  only. Without a group in `branchFilter` it named branches `origin/main`.
- `listSize: '2'` returned all 7 tags; it sets the select box's height.
- Git Parameter answered 400 on `buildWithParameters` for a tag it does not
  list, and for a tag of the job's other repository when `useRepository`
  named one. `x-git-ref` with `x-git-repository` gave the same answers.
- A *Build with Parameters* page with a `uiParameter`, a Git Parameter and
  an Active Choices parameter (uno-choice 2.8.11, sandboxed Groovy):
  HtmlUnit ran Jenkins's page scripts, Git Parameter filled its select on
  the page, and the submitted form brought all three values to the build.
  The test writes the `uiParameter`'s JSON field, which is what its form
  submits; whether HtmlUnit ran the form's module was not checked, and the
  browser suite covers module-driven forms.
- The first form page of a fresh JenkinsRule instance took 7.6 s, so the
  rename test warms the pages and fills the formFrom cache with the same
  read the page makes, and fails if it passes the 10-second window.

### Limits

- Only `file:` repositories without credentials, CLI git for Git Parameter
  and JGit for `gitRefs`. Over HTTP or SSH, Git Parameter's tag matching
  goes through the same `ls-remote` rule (Assumption: not run here).
- Not compared: `PT_REVISION`, `PT_PULL_REQUEST`, `PT_BRANCH_TAG`,
  `selectedValue`, and Git Parameter's quick filter against `search`.
- A job renamed or moved while a formFrom read for it is in flight can
  still store that read under the old name, for one cache window.
- A folder rename (as opposed to moving a job into a folder) is not tested;
  Jenkins reports it as a location change of every item inside, which
  `FormFrom.Renames` handles (Fact: `ItemListener.fireLocationChange` walks
  `Items.allItems2` of an `ItemGroup`, jenkins-core 2.541.1 bytecode).
- An approval still pending when a job moves stays approvable: approval
  keys on the code's hash, and the test approves it after the move. Which
  job name the pending list shows after the move was not checked.
