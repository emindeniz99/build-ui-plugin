# Memory: build-ui-plugin

What the next session needs to know. Read before working; update after
significant work. One dated line per entry; rewrite **Now** in place and
append to the other sections. Past 100 lines, delete the oldest Done and
Learnings entries; git history keeps them.

## Now

Feature-complete for 0.1.0. On 2026-10-27 every test passed on 2.541.1 and
2.580.1 (counts in README » Status): parameter, `input` step, `uiPanel`,
`schema-form`, `jenkinsRuns`, `gitRefs`, `argoCdApp`, optional approval,
optional sandboxed forms, `formFrom` and single-file html, the checker,
accessibility checks, `alpine3-csp`, and `alpine3`/`vue3` behind
`allowEvalRuntimes`. Dev tooling: `pnpm preview`, `js/types/build-ui.d.ts`
and `pnpm typecheck`; Git Parameter parity for `gitRefs` and integration
tests with Git Parameter, Active Choices and job renames. Release and
security workflows are ready but have never run on GitHub: in the monorepo
where the project began, Actions ran no push-triggered workflow after
2026-06-25 and a manual CI run stayed queued. The first tag waits until they
pass (owner, 2026-10-27; README » Release). Firefox and WebKit legs exist
only in CI, never run locally (only Chromium was installed). Open items:
`docs/design.md` section 10.

## Decisions

- 2026-10-23: plugin ID `build-ui`, Jenkinsfile symbol `uiParameter` — every planned surface belongs to a run
- 2026-10-23: minimum Jenkins 2.541.1, Java 17 — oldest LTS line with core CSP
- 2026-10-23: v1 runtimes `none` + `preact11-htm3`; Solid 2 after its stable release — RC is not a production dependency
- 2026-10-23: approval of form code is an admin setting, off by default (owner: "not mandatory, keep it usable as before") — built on script-security, one pending entry per form
- 2026-10-23: design lives in `docs/design.md`, not in the original design request (the RFC), which stays outside this repository
- 2026-10-23: commits go straight to `main`, no PRs (owner)
- 2026-10-23: content endpoint checks Overall/Read, not job Read — one key serves form, input step and panels
- 2026-10-23: own JSON Schema subset instead of a library — no `$ref`, ReDoS budget, unknown keywords fail loudly
- 2026-10-23: actions pinned by full commit SHA, not by tag — a tag can be moved to other code
- 2026-10-23: solid2-html not planned — lost the comparison (design.md section 7)
- 2026-10-23: gitRefs takes a job, never a URL — no SSRF; needs Item/Build
- 2026-10-23: runtime IDs and schema keywords live in contract.json, read by Java and the checker
- 2026-10-23: release by tag v<semver> (jenkins-ui-v<semver> while in the monorepo); the .hpi goes to GitHub Releases, never git
- 2026-10-23: ArgoCD servers are admin config (UI or JCasC); the browser names an app, never a server URL; cache key is URL + credentials + app
- 2026-10-23: x-git-ref checks run on every queued build (defaults included) in RefGuard, a QueueTaskDispatcher that lists refs on a pool thread and cancels the item; never in QueueGuard — review finding: Git I/O under the queue lock stalled every job
- 2026-10-23: Dependabot cooldowns: minor and patch 7 days, matching pnpm minimumReleaseAge; major 30 days
- 2026-10-23: alpine3-csp ships like any runtime; alpine3 and vue3 need eval and sit behind allowEvalRuntimes (off) — they break the day CSP is enforced; host.js probes eval in the browser because a proxy may add its own CSP
- 2026-10-23: sandbox = isolated `<iframe sandbox="allow-scripts">`, not DOM mirroring (owner); opt-in per form plus admin `sandboxAll`, off by default
- 2026-10-23: sandboxed module loads through a one-time token (128 bits, single use, 60 s, 10 000 max) from an unprotected action with CORS `*`; the protected frame endpoint does the Overall/Read check
- 2026-10-23: approval text marks sandboxed code; a sandbox-only approval does not cover the same code unsandboxed, a page approval covers the sandbox
- 2026-10-27: `formFrom` reads the form folder from the branch head through SCMFileSystem (owner) — inline forms change only after a build; only multibranch branch jobs and Pipeline-from-SCM; others fail loud
- 2026-10-27: formFrom cache key is job + folder, not revision — SCMFileSystem knows the revision only after opening; 10 s, 256 entries, failures cached, 20 s read bound
- 2026-10-27: single-file html (owner): the controller splits one module script and styles out of `html`/`form.html`; classic/src scripts and on*= are errors; uiPanel html not split
- 2026-10-27: the preview reuses check.mjs exports instead of a second parser; its validator ports Schema.validate (JS regex, doubles: documented in schema.mjs); loopback bind plus a Host check against DNS rebinding
- 2026-10-27: TypeScript 6.0.3 for typecheck, not 7.0.2 — one package without dependencies or install scripts; 7.0.2 pulls a native binary per platform. `strict: false`: TS 6's strict default fails every example module
- 2026-10-27: gitRefs `repository` is a glob of the job's remote URLs (Git Parameter matches URLs too, by regex); `search` is a case-insensitive substring; paging adds `offset` and a `total` object, shape otherwise unchanged; `x-git-repository` narrows `x-git-ref`
- 2026-10-27: where Git Parameter differs on purpose (tag path tail match, rc before release, `origin/` prefix, first repository only, listSize), gitRefs keeps its behaviour and GitParameterParityTest asserts the difference
- 2026-10-27: review fixes: formFrom in a multibranch job reads the revision `getTrustedRevision` picks, as SCMBinder does for the Jenkinsfile; gitRefs drops `user:password@` from scheme URLs in answers, errors and globs (scp-like `git@host:x` stays); single-file html is read as the HTML tokenizer reads it and the markup left after the split is checked

## Learnings

- 2026-10-23: `hpi:run` loads test-scope plugins (Pipeline) and serves `src/main/webapp` live; give each Jenkins line its own `-DJENKINS_HOME`
- 2026-10-23: pnpm's isolated layout breaks a browser import map over node_modules; the lab uses `nodeLinker: hoisted`
- 2026-10-23: input step console shows "Input requested", not the message, when it has parameters
- 2026-10-23: Preact 11 runs effect cleanups after render(null) returns; wait before asserting a timer stopped
- 2026-10-23: the Git plugin refuses file: URLs unless hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true (tests only)
- 2026-10-23: Jenkins's "/" search shortcut steals keys from inputs in a shadow root; host.js stops keydown propagation from editable targets
- 2026-10-23: Jenkins `--error-color` and `--text-color-secondary` fail 4.5:1 contrast; mix them toward `--text-color` with color-mix
- 2026-10-23: a parameterized job answers a bare POST /build with 400; use buildWithParameters
- 2026-10-23: Alpine.start() inits page markup outside the form and never reaches shadow roots; use initTree(root) and destroyTree(root). Alpine.data names are page-global: a later x-if component got the other form's definition
- 2026-10-23: system property jenkins.security.csp.CspHeader.headerName=Content-Security-Policy-Report-Only beats hpi:run's enforced default and is read per request; set it via scriptText
- 2026-10-23: ScriptApproval.configuring() saves to disk on every call and auto-approves when security is off; getPendingScripts() is restricted API
- 2026-10-23: Queue.schedule2 calls QueueDecisionHandler.shouldSchedule with the global queue lock held; do no network I/O there
- 2026-10-23: XStream (config.xml, build.xml) sets fields without setters; validate in readResolve, and do not throw there (robust converters drop the element silently)
- 2026-10-23: Stapler binds only keys a form sends; a GlobalConfiguration with a repeatable list must reset it in configure()
- 2026-10-23: HttpRequest.timeout ends when headers arrive (JDK 17/21); bound the body with sendAsync().get(timeout)
- 2026-10-23: sandboxed frame: Jenkins CSP applies to the frame document; page `default-src 'self'` stops the frame navigating to another host; Playwright reports CSP-blocked images as failed with "csp"
- 2026-10-23: a `display: block` host lets child margins collapse out, so a ResizeObserver height is short; use `display: flow-root`
- 2026-10-23: axe runs page-level rules (landmark-one-main, page-has-heading-one, region) on an iframe's document; prove axe reaches the frame with a deliberate violation
- 2026-10-23: Stapler answers 500 for a decoded `..` path segment before plugin code runs
- 2026-10-23: pnpm 12 installs before `pnpm run` (verify-deps-before-run) and reconciled a node_modules symlinked from another checkout ("Packages: -26"); pass --config.verify-deps-before-run=false or run `node scripts/...` directly
- 2026-10-27: Pipeline `params` calls getDefaultParameterValue of every job parameter (ParamsVariable.getValue); a throwing default fails the build that defines it
- 2026-10-27: SCMSource.SourceByItem and SCMHead.HeadByItem (scm-api) find a branch job's source and head; no branch-api dependency needed
- 2026-10-27: Jelly escape-by-default turns `<` into `&lt;` but leaves `>` and `"` in text; assert on `&lt;script>`
- 2026-10-27: TypeScript 6 defaults to `strict`; in checkJs that types `useState([])` as `never[]` and flags every unannotated parameter
- 2026-10-27: Node's `fetch` ignores a caller's Host header; test Host checks with `node:http`
- 2026-10-27: a glob compiled to regex backtracks: `.*a.*a.*a.*a.*b` took 20 s on 200 `a`s (Java 21); Globs now matches without regex
- 2026-10-27: a JenkinsRule parameter on both @BeforeEach and the test method starts two Jenkins; the second fails with "Failed to bind" and "theInstance was not cleared"
- 2026-10-27: caches keyed by job full name need an ItemListener (onLocationChanged, onDeleted); fireLocationChange also visits every item inside a moved folder
- 2026-10-27: a cold JenkinsRule form page took 7.6 s; a test that must stay inside a 10 s cache window warms the pages first and times itself
- 2026-10-27: `git commit --only` refuses untracked and new files (`pathspec did not match`); stage them with git add
- 2026-10-27: scm-api's tests jar (BOM classifier `tests`) has MockSCMSource but no SCMFileSystem builder for it; FormFromTest adds one with @TestExtension. Trust needs `MockRepositoryFlags.TRUST_AWARE` on the repository and `MockChangeRequestFlags.UNTRUSTED` on the change request
- 2026-10-27: browsers end a comment at `<!-->`, `<!--->` and `--!>`, split attributes at `/` and after a closing quote, and count only tab, LF, FF, CR and space as space (checked with innerHTML in Chromium)
- 2026-10-27: in Node, `rest.endsWith()` or `rest[i]` on a string built with `+=` flattens it each call; once per element made splitHtml quadratic (40 000 styles: 3.5 s)
- 2026-10-27: `ExtensionList.add(int, T)` ignores the index; a test that needs its own extension first removes the others

## Done

- 2026-10-27: four review findings fixed (formFrom trust, single-file handler bypasses in Java and the checker, URL userinfo in gitRefs and formFrom errors, lab server bind); full `mvn verify` on 2.541.1 only
- 2026-10-27: local preview (`js/preview/`), module types and `typecheck`, `examples/preview-data.json`; 14 Node + 3 browser tests
- 2026-10-27: Git Parameter parity for gitRefs and the integration matrix with Git Parameter, Active Choices, rename and folder move; fixed glob backtracking and stale formFrom reads after a rename (docs/design.md section 16). Ran on 2.541.1 only
- 2026-10-27: `formFrom` (Pipeline from SCM, multibranch) and single-file html, checker rules, `examples/form-from/`
- 2026-10-23: sandboxed forms (iframe, token endpoint, MessageChannel protocol), 15 JenkinsRule + 10 browser + 1 checker tests on 2.541.1
- 2026-10-23: argoCdApp provider, JCasC, accessibility suite, example Jenkinsfiles under e2e, full suites green on 2.541.1 and 2.580.1
