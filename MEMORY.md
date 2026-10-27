# Memory — jenkins-ui

What the next session needs to know. Read before working; update after
significant work; prune past ~100 lines. Details: `.claude/skills/memory/SKILL.md`.

## Now

Feature-complete for 0.1.0 and tested on 2.541.1 and 2.580.1 (47 JenkinsRule,
18 plain unit, 26 browser, 20 checker tests; Maven counts 74 Java tests: our 65
plus 9 from the plugin parent, 1 skipped): parameter, `input` step, `uiPanel`,
`schema-form`, `jenkinsRuns`, `gitRefs`, `argoCdApp`, optional approval, optional sandboxed forms, the Jenkinsfile
checker, accessibility checks, runtimes `alpine3-csp`, and `alpine3`/`vue3`
behind `allowEvalRuntimes` (2.541.1 only so far: +9 JenkinsRule, +7 browser,
+1 checker test). 2026-10-27: `formFrom` and single-file html, 2.541.1 only
(+14 JenkinsRule, +11 unit, +2 browser, +6 checker; totals now 85 JenkinsRule,
29 unit, 45 browser, 28 checker). Release and security workflows ready; the first tag comes after
graduation (owner, 2026-10-27). GitHub Actions has run no push-triggered workflow in this repo since
2026-06-25 and the manual jenkins-ui CI run stayed queued, so neither
workflow has run on GitHub yet. Firefox and WebKit legs exist only in CI,
never run locally (only Chromium is installed here). Open items:
`docs/design.md` section 10.

## Decisions

- 2026-10-23: plugin ID `build-ui`, Jenkinsfile symbol `uiParameter` — every planned surface belongs to a run
- 2026-10-23: minimum Jenkins 2.541.1, Java 17 — oldest LTS line with core CSP
- 2026-10-23: v1 runtimes `none` + `preact11-htm3`; Solid 2 after its stable release — RC is not a production dependency
- 2026-10-23: approval of form code is an admin setting, off by default (owner: "not mandatory, keep it usable as before") — built on script-security, one pending entry per form
- 2026-10-23: design lives in `docs/design.md`, not in issue #94 — the issue stays behind at graduation
- 2026-10-23: commits go straight to `main`, no PRs (owner)
- 2026-10-23: content endpoint checks Overall/Read, not job Read — one key serves form, input step and panels
- 2026-10-23: own JSON Schema subset instead of a library — no `$ref`, ReDoS budget, unknown keywords fail loudly
- 2026-10-23: actions pinned by SHA (graduation rule) although siblings pin by tag
- 2026-10-23: solid2-html not planned — lost the comparison (design.md section 7)
- 2026-10-23: gitRefs takes a job, never a URL — no SSRF; needs Item/Build
- 2026-10-23: runtime IDs and schema keywords live in contract.json, read by Java and the checker
- 2026-10-23: release by tag jenkins-ui-v<semver>; the .hpi goes to GitHub Releases, never git
- 2026-10-23: ArgoCD servers are admin config (UI or JCasC); the browser names an app, never a server URL; cache key is URL + credentials + app
- 2026-10-23: x-git-ref checks run on every queued build (defaults included) in RefGuard, a QueueTaskDispatcher that lists refs on a pool thread and cancels the item; never in QueueGuard — review finding: Git I/O under the queue lock stalled every job
- 2026-10-23: Dependabot cooldowns for jenkins-ui: minor and patch 7 days, matching pnpm minimumReleaseAge; major 30 days
- 2026-10-23: alpine3-csp ships like any runtime; alpine3 and vue3 need eval and sit behind allowEvalRuntimes (off) — they break the day CSP is enforced; host.js probes eval in the browser because a proxy may add its own CSP
- 2026-10-23: sandbox = isolated `<iframe sandbox="allow-scripts">`, not DOM mirroring (owner); opt-in per form plus admin `sandboxAll`, off by default
- 2026-10-23: sandboxed module loads through a one-time token (128 bits, single use, 60 s, 10 000 max) from an unprotected action with CORS `*`; the protected frame endpoint does the Overall/Read check
- 2026-10-23: approval text marks sandboxed code; a sandbox-only approval does not cover the same code unsandboxed, a page approval covers the sandbox
- 2026-10-27: `formFrom` reads the form folder from the branch head through SCMFileSystem (owner) — inline forms change only after a build; only multibranch branch jobs and Pipeline-from-SCM; others fail loud
- 2026-10-27: formFrom cache key is job + folder, not revision — SCMFileSystem knows the revision only after opening; 10 s, 256 entries, failures cached, 20 s read bound
- 2026-10-27: single-file html (owner): the controller splits one module script and styles out of `html`/`form.html`; classic/src scripts and on*= are errors; uiPanel html not split

## Learnings

- 2026-10-22: `npm init -y --prefix X` ignores `--prefix` and writes `package.json` to the current directory
- 2026-10-22: Preact renders arrays on undefined custom elements as attributes and the value is lost; wait for `customElements.whenDefined` first
- 2026-10-23: `@jsonforms/vanilla-renderers` needs React; it is not a no-React option
- 2026-10-23: Jenkins's real CSP allows `'unsafe-inline'` styles but no eval; the lab's guess was stricter on styles
- 2026-10-23: Java 21 regex runs `^(a+)+$` in linear time; use `^(.*?,){14}P` to test a ReDoS guard
- 2026-10-23: `.jenkins-input` overrides the `hidden` attribute; also set `style.display`
- 2026-10-23: the build form page answers 405 on purpose; filter it from browser console checks
- 2026-10-23: `hpi:run` loads test-scope plugins (Pipeline) and serves `src/main/webapp` live; give each Jenkins line its own `-DJENKINS_HOME`
- 2026-10-23: pnpm's isolated layout breaks a browser import map over node_modules; the lab uses `nodeLinker: hoisted`
- 2026-10-23: input step console shows "Input requested", not the message, when it has parameters
- 2026-10-23: Preact 11 runs effect cleanups after render(null) returns; wait before asserting a timer stopped
- 2026-10-23: Solid 2 html sets prop: bindings before the element is connected; plain custom elements lose them
- 2026-10-23: the Git plugin refuses file: URLs unless hudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true (tests only)
- 2026-10-23: Jenkins's "/" search shortcut steals keys from inputs in a shadow root; host.js stops keydown propagation from editable targets
- 2026-10-23: Jenkins `--error-color` and `--text-color-secondary` fail 4.5:1 contrast; mix them toward `--text-color` with color-mix
- 2026-10-23: a parameterized job answers a bare POST /build with 400; use buildWithParameters
- 2026-10-23: `git commit --only` refuses untracked files; use the git add fallback
- 2026-10-23: Alpine.start() inits page markup outside the form and never reaches shadow roots; use initTree(root) and destroyTree(root). Alpine.data names are page-global: a later x-if component got the other form's definition
- 2026-10-23: system property jenkins.security.csp.CspHeader.headerName=Content-Security-Policy-Report-Only beats hpi:run's enforced default and is read per request; set it via scriptText
- 2026-10-23: pnpm 12 installs before `pnpm run` (verify-deps-before-run); with node_modules symlinked from the main checkout pass --config.verify-deps-before-run=false
- 2026-10-23: ScriptApproval.configuring() saves to disk on every call and auto-approves when security is off; getPendingScripts() is restricted API
- 2026-10-23: Queue.schedule2 calls QueueDecisionHandler.shouldSchedule with the global queue lock held; do no network I/O there
- 2026-10-23: XStream (config.xml, build.xml) sets fields without setters; validate in readResolve, and do not throw there (robust converters drop the element silently)
- 2026-10-23: Stapler binds only keys a form sends; a GlobalConfiguration with a repeatable list must reset it in configure()
- 2026-10-23: HttpRequest.timeout ends when headers arrive (JDK 17/21); bound the body with sendAsync().get(timeout)
- 2026-10-23: sandboxed frame: Jenkins CSP applies to the frame document; page `default-src 'self'` stops the frame navigating to another host; Playwright reports CSP-blocked images as failed with "csp"
- 2026-10-23: a `display: block` host lets child margins collapse out, so a ResizeObserver height is short; use `display: flow-root`
- 2026-10-23: axe runs page-level rules (landmark-one-main, page-has-heading-one, region) on an iframe's document; prove axe reaches the frame with a deliberate violation
- 2026-10-23: Stapler answers 500 for a decoded `..` path segment before plugin code runs
- 2026-10-23: `pnpm run` (pnpm 12) reconciled the shared node_modules before running a script ("Packages: -26"); run `node scripts/...` directly against a symlinked node_modules
- 2026-10-27: Pipeline `params` calls getDefaultParameterValue of every job parameter (ParamsVariable.getValue); a throwing default fails the build that defines it
- 2026-10-27: SCMSource.SourceByItem and SCMHead.HeadByItem (scm-api) find a branch job's source and head; no branch-api dependency needed
- 2026-10-27: Jelly escape-by-default turns `<` into `&lt;` but leaves `>` and `"` in text; assert on `&lt;script>`

## Done

- 2026-10-27: `formFrom` (Pipeline from SCM, multibranch) and single-file html, checker rules, `examples/form-from/`

- 2026-10-23: sandboxed forms (iframe, token endpoint, MessageChannel protocol), 15 JenkinsRule + 10 browser + 1 checker tests on 2.541.1

- 2026-10-23: argoCdApp provider, JCasC, accessibility suite, example Jenkinsfiles under e2e, full suites green on 2.541.1 and 2.580.1
- 2026-10-23: checker CLI, Preact vs Solid 2 comparison, gitRefs provider, release workflow and CHANGELOG for 0.1.0
- 2026-10-23: plugin built and Phase 0 passed (32 JenkinsRule + 8 browser tests on 2.541.1 and 2.580.1), CI workflow added
- 2026-10-23: renderer research (16 options, strict CSP), Fable and ChatGPT design reviews, design agreed
