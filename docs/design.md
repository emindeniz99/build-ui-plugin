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
original RFC is issue #94; the design here replaces it where they disagree.

## 1. Decisions

| Topic | Decision | Reason |
|---|---|---|
| Plugin ID | `build-ui`, groupId `io.jenkins.plugins`, Jenkinsfile symbol `uiParameter` | All planned surfaces belong to a run. Follows update-center naming rules, so hosting there later costs nothing. |
| Publishing | GitHub Releases (`.hpi`) first; the Jenkins update center at graduation | No hosting review on the critical path. |
| Jenkins / Java | Minimum 2.541.1, Java 17 bytecode; CI covers 2.541.x and the current LTS | 2.541 is the oldest LTS line with core CSP. |
| Core | Framework-agnostic; knows only the `mount` contract (section 2) | A runtime can come and go without a core rewrite. |
| v1 runtimes | `none` and `preact11-htm3` | One stable React-style renderer plus plain JS. |
| Later runtimes | `solid2-html` not planned: it lost the comparison in section 7 | Four times larger, and breaks plain custom elements even when they are defined first. |
| Eval runtimes (Vue, Alpine) | Only behind a global admin setting, off by default | Jenkins runs CSP in report-only mode by default, so these would work for months and break on the day an admin enforces CSP. |
| Component library | None in v1; packs (Web Awesome and others) stay possible later | Both motivating forms need inputs, checkboxes and cards only. |
| Approval of form code | An admin setting, off by default (owner, 2026-10-23), built on script-security | The owner trusts everyone with push access and wants no friction by default; other Jenkins setups can opt in. |
| Surfaces, in order | Build with Parameters form, then the `input` step, then panels on the build page (`uiPanel` step). All three are built. | The `input` step is the same form problem; build-page panels are a different product. |
| Declarative no-JS tier | Built as the `schema-form` runtime, which exports `mount` itself, so a job needs no module | It needs nothing from the core and proves the contract with a second consumer. |
| Data providers | `DataProvider` extension point; ships `jenkinsRuns` (Item/Read), `gitRefs` (Item/Build, optional Git plugin) and `argoCdApp` (admin-configured servers) | No provider takes a URL from the browser: `gitRefs` takes a job, `argoCdApp` a server name and an app name matched against an admin glob. That rules out SSRF through providers, and credentials stay on the controller. |
| Parked | TSX through swc4j, Remote DOM worker tier, Web Awesome pack | No current need justifies the cost. |

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
4. If `mount` throws or rejects, the host shows the error and a JSON textarea
   with schema errors, so the user can still start a build.
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
  a production Jenkins, is tested only in the lab; it is the looser case.

## 5. Validation (Tested)

- `UiParameterDefinition` extends `SimpleParameterDefinition`, so the
  Pipeline `build` step converts string values through `createValue(String)`
  and validates them.
- One JSON Schema check runs on the controller for every path that creates a
  value: the form and `buildWithParameters` answer 400 with the reason, the
  `build` step fails with it, and `QueueGuard` (a `QueueDecisionHandler`)
  refuses to queue a hand-built invalid `ParametersAction`.
- Limits: 256 KiB per document, nesting depth 32, 1000 array items, 1000
  object properties, no duplicate keys, patterns up to 256 characters. All
  patterns of one document share a 50 ms budget: a per-string budget let a
  value of thousands of strings cost minutes of controller CPU. Numbers parse
  as exact decimals (a double turned `1e400` into Infinity), and `enum`,
  `const` and `uniqueItems` compare them by value. No `$ref`; any keyword
  outside the subset fails when the job is configured.
- `schema` and `defaultJson` must parse when the job is configured. The
  default is checked against the schema when a build uses it, and by the form
  check on the freestyle configuration page; an invalid default fails that
  build with the reason.
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
| Pass | vanilla JS, vanilla custom element, Preact 11.0.1 + htm 3.1.1 (hooks and signals), lit-html 3.3.3, LitElement, Solid 2.0.0-rc.17 + `@solidjs/html`, uhtml 5.0.9, Arrow 1.0.6, VanJS 1.6.1, Mithril 2.3.8, `@alpinejs/csp` 3.17.5 |
| Fail with `EvalError` | `solid-js/html` 1.9.17, Vue 3.5.44 runtime compiler, petite-vue 0.4.1, Alpine 3.17.5 |

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
- Runtime bundles, minified and gzipped: `preact11-htm3` 6.8 KB.

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

- Eval-based runtimes behind an admin setting (none ships, so no setting yet).
- A sandbox for untrusted code (Remote DOM): declined by the owner for now.
- A browser test of approval: `hpi:run` runs without security, where
  script-security approves everything, so `FormApprovalTest` covers approval
  through JenkinsRule pages instead.
- A release: the workflow and `CHANGELOG.md` exist, and a local rehearsal of
  its steps built `build-ui.hpi` 0.1.0, but no tag is pushed yet.
- Firefox and WebKit runs: CI runs them on the current LTS line, but this
  container has only Chromium, so no Firefox or WebKit run has happened yet.

## 11. Compatibility and upgrades (Policy)

The plugin follows semantic versioning; a release is a `jenkins-ui-vX.Y.Z` tag.
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

Size budgets (gzip): host 3 KB, `preact11-htm3` 8 KB, `schema-form` 9 KB.
`pnpm vendor:check` fails past a budget, so growth past it is a decision
taken in the commit that raises it. Today: 2.3, 6.7 and 7.3 KB.
