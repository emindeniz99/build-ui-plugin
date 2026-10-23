pipeline {
  agent none
  parameters {
    uiParameter(
      name: 'BUILD_CONFIG',
      schema: '''{ "type": "object", "required": ["branch", "services"], "additionalProperties": false,
        "properties": { "branch": { "type": "string", "minLength": 1 },
          "services": { "type": "array", "minItems": 1, "uniqueItems": true, "items": { "enum": ["api", "worker", "web"] } },
          "deploy": { "type": "boolean" }, "env": { "enum": ["dev", "staging", "prod"] } } }''',
      defaultJson: '{ "branch": "main", "services": ["api"], "deploy": false }',
      css: 'fieldset { border: 1px solid var(--input-border, #ccc); border-radius: 6px; margin: 0 0 8px; }',
      module: '''
export default function mount({ root, ui, runtime: { html, render, useState, useEffect } }) {
  const Section = ({ title, children }) => html`<fieldset><legend>${title}</legend>${children}</fieldset>`
  function App() {
    const [c, setC] = useState(ui.value)
    const [runs, setRuns] = useState([])
    useEffect(() => { ui.query('jenkinsRuns', { job: ui.job, limit: 3 }).then(setRuns, () => {}) }, [])
    const set = (patch) => {
      const next = { ...c, ...patch }
      if (!next.deploy) delete next.env
      else next.env = next.env || 'dev'
      setC(next)
      ui.setValue(next)
    }
    const toggle = (s) => set({ services: c.services.includes(s) ? c.services.filter((x) => x !== s) : [...c.services, s] })
    return html`
      <${Section} title="Source">
        <label>Branch <input name="branch" value=${c.branch} onInput=${(e) => set({ branch: e.currentTarget.value })} /></label>
      <//>
      <${Section} title="Services">
        ${['api', 'worker', 'web'].map((s) => html`<label><input type="checkbox" name=${s}
          checked=${c.services.includes(s)} onChange=${() => toggle(s)} /> ${s}</label>`)}
      <//>
      <${Section} title="Deploy">
        <label><input type="checkbox" name="deploy" checked=${c.deploy} onChange=${(e) => set({ deploy: e.currentTarget.checked })} /> Deploy</label>
        ${c.deploy && html`<select name="env" value=${c.env} onChange=${(e) => set({ env: e.currentTarget.value })}>
          ${['dev', 'staging', 'prod'].map((e) => html`<option>${e}</option>`)}</select>`}
      <//>
      <${Section} title="Recent builds">
        <ul class="runs">${runs.map((r) => html`<li>#${r.number} ${r.result || 'running'}</li>`)}</ul>
      <//>`
  }
  render(html`<${App} />`, root)
  return () => render(null, root)
}''')
  }
  stages {
    stage('Build') {
      steps { echo "BUILD_CONFIG=${params.BUILD_CONFIG}" }
    }
  }
}
