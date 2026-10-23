# Memory — jenkins-ui

What the next session needs to know. Read before working; update after
significant work; prune past ~100 lines. Details: `.claude/skills/memory/SKILL.md`.

## Now

Feature-complete for 0.1.0 and tested on 2.541.1 and 2.580.1: parameter,
`input` step, `uiPanel`, `schema-form`, `jenkinsRuns`, `gitRefs`, the
Jenkinsfile checker. Release workflow ready; no tag pushed (owner decides).
GitHub Actions has run no push-triggered workflow in this repo since
2026-06-25 and the manual jenkins-ui CI run stayed queued, so neither
workflow has run on GitHub yet. Open items: `docs/design.md` section 10.

## Decisions

- 2026-10-23: plugin ID `build-ui`, Jenkinsfile symbol `uiParameter` — every planned surface belongs to a run
- 2026-10-23: minimum Jenkins 2.541.1, Java 17 — oldest LTS line with core CSP
- 2026-10-23: v1 runtimes `none` + `preact11-htm3`; Solid 2 after its stable release — RC is not a production dependency
- 2026-10-23: no approval of form code (owner) — owner trusts everyone with push access and wants no friction; approval maybe later, not planned
- 2026-10-23: design lives in `docs/design.md`, not in issue #94 — the issue stays behind at graduation
- 2026-10-23: commits go straight to `main`, no PRs (owner)
- 2026-10-23: content endpoint checks Overall/Read, not job Read — one key serves form, input step and panels
- 2026-10-23: own JSON Schema subset instead of a library — no `$ref`, ReDoS budget, unknown keywords fail loudly
- 2026-10-23: actions pinned by SHA (graduation rule) although siblings pin by tag
- 2026-10-23: solid2-html not planned — lost the comparison (design.md section 7)
- 2026-10-23: gitRefs takes a job, never a URL — no SSRF; needs Item/Build
- 2026-10-23: runtime IDs and schema keywords live in contract.json, read by Java and the checker
- 2026-10-23: release by tag jenkins-ui-v<semver>; the .hpi goes to GitHub Releases, never git

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

## Done

- 2026-10-23: checker CLI, Preact vs Solid 2 comparison, gitRefs provider, release workflow and CHANGELOG for 0.1.0
- 2026-10-23: plugin built and Phase 0 passed (32 JenkinsRule + 8 browser tests on 2.541.1 and 2.580.1), CI workflow added
- 2026-10-23: renderer research (16 options, strict CSP), Fable and ChatGPT design reviews, design agreed
