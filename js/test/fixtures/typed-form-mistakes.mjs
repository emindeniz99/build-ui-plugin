// @ts-check
// Mistakes the types exist to catch before anyone opens Build with Parameters.

/** @type {import('../../types/build-ui').MountFor<'preact11-htm3', { branch: string }>} */
export default function mount({ root, ui, runtime: { html, render, useStat } }) {
  ui.setValue({ branch: 1 })
  ui.query('gitRefs', { tags: 'v*' })
  render(html`<p>${ui.jobName}</p>`, root)
}
