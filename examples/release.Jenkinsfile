// Cut a release: pick the branch to build and the tag to count the next version
// from, see the tag the build will create, or type a special version instead.
// The controller rejects a branch or tag that does not exist (x-git-ref).
pipeline {
  agent any
  parameters {
    uiParameter(
      name: 'RELEASE',
      schema: '''{ "type": "object", "required": ["branch", "baseTag", "bump"], "additionalProperties": false,
        "properties": {
          "branch": { "type": "string", "x-git-ref": "branch" },
          "baseTag": { "type": "string", "x-git-ref": "tag" },
          "bump": { "enum": ["patch", "minor", "major"] },
          "override": { "type": "string", "pattern": "^[0-9A-Za-z.+-]{1,40}$" } } }''',
      defaultJson: '{ "branch": "main", "baseTag": "v1.0.0", "bump": "patch" }',
      module: '''
const next = (tag, bump) => {
  const m = /^v?(\\d+)\\.(\\d+)\\.(\\d+)$/.exec(tag)
  if (!m) return null
  const [major, minor, patch] = m.slice(1).map(Number)
  if (bump === 'major') return `v${major + 1}.0.0`
  if (bump === 'minor') return `v${major}.${minor + 1}.0`
  return `v${major}.${minor}.${patch + 1}`
}

export default function mount({ root, ui, runtime: { html, render, useState, useEffect } }) {
  function App() {
    const [c, setC] = useState(ui.value)
    const [refs, setRefs] = useState({ branches: [], tags: [] })
    const [error, setError] = useState(null)
    useEffect(() => {
      ui.query('gitRefs', { job: ui.job, tags: 'v*', sort: 'version-desc', limit: 50 }).then(setRefs, (e) => setError(e.message))
    }, [])
    const set = (patch) => {
      const next = { ...c, ...patch }
      if (!next.override) delete next.override
      setC(next)
      ui.setValue(next)
    }
    const preview = c.override || next(c.baseTag, c.bump)
    return html`
      ${error && html`<p role="alert">${error}</p>`}
      <label>Branch <select name="branch" value=${c.branch} onChange=${(e) => set({ branch: e.currentTarget.value })}>
        ${refs.branches.map((b) => html`<option>${b.name}</option>`)}</select></label>
      <label>Count from <select name="baseTag" value=${c.baseTag} onChange=${(e) => set({ baseTag: e.currentTarget.value })}>
        ${refs.tags.map((t) => html`<option>${t.name}</option>`)}</select></label>
      <fieldset><legend>Bump</legend>${['patch', 'minor', 'major'].map((b) => html`<label>
        <input type="radio" name="bump" value=${b} checked=${c.bump === b} onChange=${() => set({ bump: b })} /> ${b}</label>`)}</fieldset>
      <label>Special version <input name="override" placeholder="for example 1.0.nightly-rc" value=${c.override || ''}
        onInput=${(e) => set({ override: e.currentTarget.value.trim() })} /></label>
      <p class="preview">Builds <strong>${c.branch}</strong> and tags it <strong name="next-tag">${preview || '(base tag is not vX.Y.Z)'}</strong></p>`
  }
  render(html`<${App} />`, root)
  return () => render(null, root)
}''')
  }
  stages {
    stage('Release') {
      steps {
        // Load this Jenkinsfile with "Pipeline script from SCM"; the checkout
        // is what tells gitRefs which repository to list.
        checkout scm
        echo "RELEASE=${params.RELEASE}"
      }
    }
  }
}
