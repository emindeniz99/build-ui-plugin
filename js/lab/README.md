# Renderer lab

The same small form written in 15 libraries, served with a strict Content
Security Policy and driven by Playwright. It produced the measurements in
[`../../docs/design.md`](../../docs/design.md), section 6.

```bash
pnpm --dir projects/jenkins-ui/js install
pnpm --dir projects/jenkins-ui/js lab          # serves http://127.0.0.1:4321
node projects/jenkins-ui/js/lab/run3.mjs       # all ex-* pages, strict CSP and CSP off
node projects/jenkins-ui/js/lab/run4.mjs       # eval probe: enforced, report-only, off
node projects/jenkins-ui/js/lab/run5.mjs       # style, import and inline-handler probes
node projects/jenkins-ui/js/lab/run-compare.mjs  # Preact vs Solid 2 on one complex form
node projects/jenkins-ui/js/lab/alpine-shadow-probe.mjs  # Alpine lifecycle inside shadow roots
node projects/jenkins-ui/js/lab/eval-runtime-probe.mjs   # alpine3 and vue3 bundles, enforced and report-only CSP
```

Open a page by hand at `/page/<name>`, for example `/page/ex-preact-htm`.
Add `?csp=off` to drop the policy or `?csp=report` to send it as
`Content-Security-Policy-Report-Only`.

The lab sends its own policy by default. Set `LAB_CSP` to replay a real one,
such as the header Jenkins sends; the server adds the import map's hash to
`script-src`, because the lab page needs an inline import map and a plugin
page does not.
