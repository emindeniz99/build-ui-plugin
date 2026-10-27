# Contributing

This repository holds the Build UI Jenkins plugin (plugin ID `build-ui`) and
its JavaScript tooling in `js/`. Read [`docs/design.md`](./docs/design.md)
before you change behaviour; it records each decision and what backs it.
[`MEMORY.md`](./MEMORY.md) lists the current state and the gotchas that cost
time before.

## Build and test

You need Java 17 or newer, Maven 3.9, Node 24.22.1 and pnpm 12.14.0
(`js/package.json` pins both). Run every command from the repository root.

```bash
# Plugin on the minimum line (2.541.1): build, JenkinsRule tests, SpotBugs
mvn verify

# Plugin on the current LTS line; CI keeps the current values in
# .github/workflows/ci.yml
mvn verify -Djenkins.baseline=2.580 -Djenkins.version=2.580.1 -Dbom.version=7237.v5071b_f4589f1

# JS: runtime bundles, checker, preview and type tests
pnpm --dir js install --frozen-lockfile
pnpm --dir js vendor:check
pnpm --dir js test
pnpm --dir js check examples/*.Jenkinsfile

# Preview in a browser, no Jenkins needed
pnpm --dir js exec playwright install chromium
pnpm --dir js preview:e2e
```

The browser suite runs against a live Jenkins. Start one in a second
terminal, wait for "Jenkins is fully up", then run the suite:

```bash
mvn hpi:run -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true
pnpm --dir js e2e                                   # Chromium
BROWSERS=chromium,firefox,webkit pnpm --dir js e2e  # what CI runs on the current LTS line
```

`hpi:run` serves `src/main/webapp` live. Give each Jenkins line its own
`-DJENKINS_HOME` if you switch between them.

After you edit anything in `js/runtimes/`, run `pnpm --dir js vendor` and
commit the rebuilt bundles in `src/main/webapp/runtimes/`. `vendor:check`
fails CI when a bundle is stale.

Run the tests your change touches, and say in the commit or pull request
which ones you ran and on which Jenkins line. A test you skipped counts as
not run.

## Project rules

- The plugin never adds `unsafe-eval` to Jenkins's Content Security Policy and
  never loosens it. Runtimes that need eval stay behind the
  `allowEvalRuntimes` admin setting, off by default.
- No data provider accepts a URL from the browser. The browser names a job,
  an application or a glob over remotes the job already has; the controller
  contacts only servers that a job or an admin configured.
- Credentials stay on the controller. No answer to the browser carries a
  token, a password or another parameter's value.
- The controller validates every value on every path that starts a build:
  the form, `buildWithParameters`, the `build` step and the queue.
- Examples, tests and docs use generic names only: `example.com` hosts,
  made-up services and jobs. Never commit a company name, an internal
  hostname, a real Jenkins or ArgoCD URL, or a person other than the
  maintainer.
- Dependencies must be at least 7 days old. pnpm enforces it
  (`minimumReleaseAge` in `js/pnpm-workspace.yaml`); apply the same rule to
  Maven and Actions updates. Pin each GitHub Action to a full commit SHA
  with its tag in a comment.
- Claims in `docs/design.md` carry a label: Fact, Measured, Tested,
  Assumption or Proposal. Keep the README's numbers (test counts, bundle
  sizes, versions) true when your change moves them, and add user-visible
  changes to `CHANGELOG.md`.
- Write code, comments, docs and commit messages in English.

## Commits

Use Conventional Commits with a mandatory scope:

```
<type>(<scope>): <subject>

<body: why the change is needed>
```

| Scope | Covers |
|---|---|
| `plugin` | `src/main/java`, `src/main/resources`, `host.js`, the sandbox frame, `pom.xml`, Java tests |
| `runtimes` | `js/runtimes/` and the bundles in `src/main/webapp/runtimes/` |
| `checker` | `js/scripts/check.mjs`, `js/scripts/typecheck.mjs`, `js/types/` and their tests |
| `preview` | `js/preview/` and its tests |
| `js` | Other JS tooling: `js/e2e/`, `js/lab/`, `package.json`, Playwright configs |
| `docs` | `README.md`, `docs/`, `CHANGELOG.md`, `examples/` |
| `ci` | `.github/` |
| `deps` | Dependency updates |

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `chore`, `build`,
`ci`, `revert`. Pick the one that fits, and `chore` when none does.

- Write the subject in the imperative, starting lowercase, with no trailing
  period. Keep the header line at 72 characters or fewer.
- Use the body to explain why: the problem, the cause, what you chose over
  the obvious alternative. The diff already shows what changed.
- One commit holds one reason. Split unrelated changes, even when you made
  them in one session. Name only that commit's paths when you stage.
- Mark a breaking change with `!` after the scope and a
  `BREAKING CHANGE:` footer with the migration.

Examples:

```
feat(plugin): add a total count to gitRefs answers
fix(preview): answer 403 to a foreign Host header
test(plugin): compare gitRefs paging with Git Parameter's list size
```

### AI attribution and signing

When an AI agent writes a commit's content, the history must show it.

- If the environment lets the agent commit under its own identity, the agent
  is the author. It never poses as the maintainer.
- If the commit goes out under a human's identity (a local session, an API or
  connector that records the signed-in account), add the agent as a trailer
  with its current model name and the vendor's attribution address, for
  example `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Sign commits when the environment provides a key, with the identity that
  owns it. An environment without signing may commit unsigned, but the
  attribution rule still applies. Never invent a signing identity or weaken
  verification to get a commit through.

## Pull requests and merging

The maintainer commits to `main` directly. Pull requests merge with a real
merge commit, which keeps every commit of the branch. Never squash and never
rebase-merge, unless the maintainer asks for it on that pull request. Keep
the source branch unless someone asks you to delete it. Never force-push
`main`, and never amend a commit that is already pushed.
