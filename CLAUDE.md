# Agent notes

Build UI is a Jenkins plugin (`build-ui`) plus JavaScript tooling in `js/`.
Before you change anything, read:

1. [`CONTRIBUTING.md`](./CONTRIBUTING.md): build and test commands, project
   rules, commit and merge rules. They apply to agents in full.
2. [`docs/design.md`](./docs/design.md): decisions, measurements and open
   questions. Check a claim there before you contradict it.
3. [`MEMORY.md`](./MEMORY.md): current state and known gotchas. Verify an
   entry against the code before you act on it, and update the file after
   significant work.

The rules agents break most often:

- Commit headers read `<type>(<scope>): <subject>`, scope mandatory, from
  the table in CONTRIBUTING.md. One commit holds one reason.
- Add `Co-Authored-By:` with your current model name to any commit you wrote
  that goes out under a human's identity.
- Merge with a real merge commit. Never squash or rebase-merge unless the
  maintainer asks on that pull request.
- Never add `unsafe-eval`, never let a provider take a URL from the browser,
  never send a credential to the browser.
- Use generic names (`example.com`) in examples and tests.
- Report a skipped or failing test as skipped or failing. Do not call the
  work done around it.
