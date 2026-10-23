# Memory — jenkins-ui

What the next session needs to know. Read before working; update after
significant work; prune past ~100 lines. Details: `.claude/skills/memory/SKILL.md`.

## Now

Feature-complete for 0.1.0 and tested on 2.541.1 and 2.580.1 (36 JenkinsRule,
14 plain unit, 18 browser, 11 checker tests; Maven counts 59 Java tests: our 50
plus 9 from the plugin parent, 1 skipped): parameter, `input` step, `uiPanel`,
`schema-form`, `jenkinsRuns`, `gitRefs`, `argoCdApp`, optional approval, the Jenkinsfile
checker, accessibility checks. Release workflow ready; no tag pushed (owner
decides). GitHub Actions has run no push-triggered workflow in this repo since
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
- 2026-10-23: x-git-ref checks run on every path the queue sees, but not on the default value (no job context)
- 2026-10-23: Dependabot cooldowns for jenkins-ui: minor and patch 7 days, matching pnpm minimumReleaseAge; major 30 days

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
- 2026-10-23: ScriptApproval.configuring() saves to disk on every call and auto-approves when security is off; getPendingScripts() is restricted API

## Done

- 2026-10-23: argoCdApp provider, JCasC, accessibility suite, example Jenkinsfiles under e2e, full suites green on 2.541.1 and 2.580.1
- 2026-10-23: checker CLI, Preact vs Solid 2 comparison, gitRefs provider, release workflow and CHANGELOG for 0.1.0
- 2026-10-23: plugin built and Phase 0 passed (32 JenkinsRule + 8 browser tests on 2.541.1 and 2.580.1), CI workflow added
- 2026-10-23: renderer research (16 options, strict CSP), Fable and ChatGPT design reviews, design agreed
