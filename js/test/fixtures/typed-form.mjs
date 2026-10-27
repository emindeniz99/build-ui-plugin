// @ts-check
// A form module typed with build-ui.d.ts, as the README shows it.

/** @typedef {{ branch: string, tag?: string }} Config */

/** @type {import('../../types/build-ui').MountFor<'preact11-htm3', Config>} */
export default function mount({ root, ui, runtime: { html, render, useState, useEffect }, signal }) {
  function App() {
    const [config, setConfig] = useState(ui.value)
    const [tags, setTags] = useState(/** @type {string[]} */ ([]))
    useEffect(() => {
      ui.query('gitRefs', { job: ui.job, tags: 'v*', sort: 'version-desc' }, { signal })
        .then((refs) => setTags(refs.tags.map((t) => t.name)), () => {})
    }, [])
    const set = (/** @type {Partial<Config>} */ patch) => {
      const next = { ...config, ...patch }
      setConfig(next)
      ui.setValue(next)
    }
    return html`<label>Branch <input value=${config.branch} onInput=${(/** @type {InputEvent} */ e) =>
      set({ branch: /** @type {HTMLInputElement} */ (e.currentTarget).value })} /></label>
      <p>${tags.length} tags, schema ${ui.schema?.type ?? 'none'}</p>`
  }
  render(html`<${App} />`, root)
  return () => render(null, root)
}
