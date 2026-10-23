# Memory — jenkins-ui

What the next session needs to know. Read before working; update after
significant work; prune past ~100 lines. Details: `.claude/skills/memory/SKILL.md`.

## Now

Design agreed (`docs/design.md`), no code yet. Next: Phase 0 step 1, run a
real Jenkins (`mvn hpi:run`) and copy its CSP header into the lab. The
renderer lab (`server.mjs`, `pages/ex-*.mjs`, `run3.mjs`) still lives in a
session scratchpad; bring it into `lab/` as a pnpm project in Phase 0.

## Decisions

- 2026-10-23: plugin ID `build-ui`, Jenkinsfile symbol `uiParameter` — every planned surface belongs to a run
- 2026-10-23: minimum Jenkins 2.541.1, Java 17 — owner's Jenkins, oldest LTS line with core CSP
- 2026-10-23: v1 runtimes `none` + `preact11-htm3`; Solid 2 after its stable release — RC is not a production dependency
- 2026-10-23: no approval of form code in v1 (owner) — owner trusts everyone with push access; approval option before public release
- 2026-10-23: design lives in `docs/design.md`, not in issue #94 — the issue stays behind at graduation
- 2026-10-23: commits go straight to `main`, no PRs (owner)

## Learnings

- 2026-10-22: `npm init -y --prefix X` ignores `--prefix` and writes `package.json` to the current directory
- 2026-10-22: Preact renders arrays on undefined custom elements as attributes and the value is lost; wait for `customElements.whenDefined` first
- 2026-10-23: `@jsonforms/vanilla-renderers` needs React; it is not a no-React option

## Done

- 2026-10-23: renderer research (16 options, strict CSP), Fable and ChatGPT design reviews, design agreed
