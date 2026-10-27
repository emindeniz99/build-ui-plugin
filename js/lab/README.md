# Renderer lab

The same small form written in 15 libraries, served with a strict Content
Security Policy and driven by Playwright. It produced the measurements in
[`../../docs/design.md`](../../docs/design.md), section 6.

```bash
pnpm --dir js install
pnpm --dir js lab                    # serves http://127.0.0.1:4321
node js/lab/run3.mjs                 # all ex-* pages, strict CSP and CSP off
node js/lab/run4.mjs                 # eval probe: enforced, report-only, off
node js/lab/run5.mjs                 # style, import and inline-handler probes
node js/lab/run-compare.mjs          # Preact vs Solid 2 on one complex form
node js/lab/alpine-shadow-probe.mjs  # Alpine lifecycle inside shadow roots
node js/lab/eval-runtime-probe.mjs   # alpine3 and vue3 bundles, enforced and report-only CSP
node js/lab/sandbox-probe.mjs        # what runs in <iframe sandbox="allow-scripts"> under Jenkins's CSP
```

The two probes take the engine as an argument (`chromium`, the default,
`firefox` or `webkit`). CI runs both in every engine of the browser job.

Run these from the repository root.

Open a page by hand at `/page/<name>`, for example `/page/ex-preact-htm`.
Add `?csp=off` to drop the policy or `?csp=report` to send it as
`Content-Security-Policy-Report-Only`.

The lab sends its own policy by default. Set `LAB_CSP` to replay a real one,
such as the header Jenkins sends; the server adds the import map's hash to
`script-src`, because the lab page needs an inline import map and a plugin
page does not.
