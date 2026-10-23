// Build and deploy to several test environments at once. Each card shows the
// last build that went to that environment, read from this job's own history.
pipeline {
  agent none
  parameters {
    uiParameter(
      name: 'DEPLOY',
      schema: '''{ "type": "object", "required": ["branch", "envs", "services", "autoDeploy"], "additionalProperties": false,
        "properties": {
          "branch": { "type": "string", "minLength": 1 },
          "envs": { "type": "array", "minItems": 1, "uniqueItems": true,
            "items": { "enum": ["env-1", "env-2", "env-3", "env-4", "env-5", "env-6", "stress-test", "nightly"] } },
          "services": { "type": "array", "minItems": 1, "uniqueItems": true, "items": { "enum": ["api", "worker", "web"] } },
          "autoDeploy": { "type": "boolean" } } }''',
      defaultJson: '{ "branch": "main", "envs": ["env-1"], "services": ["api"], "autoDeploy": true }',
      css: '''
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr)); gap: .5rem; }
        .card { border: 1px solid var(--input-border, #ccc); border-radius: .4rem; padding: .5rem; }
        .card.on { border-color: var(--accent-color, #0b6aa2); box-shadow: 0 0 0 1px var(--accent-color, #0b6aa2); }
        .last { color: color-mix(in oklch, var(--text-color-secondary) 60%, var(--text-color)); font-size: .85em; }
        .row { display: flex; gap: .75rem; align-items: center; margin: .5rem 0; flex-wrap: wrap; }''',
      module: '''
const ENVS = ['env-1', 'env-2', 'env-3', 'env-4', 'env-5', 'env-6', 'stress-test', 'nightly']
const SERVICES = ['api', 'worker', 'web']

export default function mount({ root, ui, runtime: { html, render, useState, useEffect, useMemo } }) {
  const toggle = (list, item) => list.includes(item) ? list.filter((x) => x !== item) : [...list, item]

  function App() {
    const [c, setC] = useState(ui.value)
    const [search, setSearch] = useState('')
    const [runs, setRuns] = useState([])
    const [error, setError] = useState(null)
    useEffect(() => {
      ui.query('jenkinsRuns', { job: ui.job, limit: 50, parameter: ui.name }).then(setRuns, (e) => setError(e.message))
    }, [])
    const lastByEnv = useMemo(() => {
      const last = {}
      for (const run of runs) for (const env of run.config?.envs || []) last[env] ||= run
      return last
    }, [runs])
    const set = (patch) => { const next = { ...c, ...patch }; setC(next); ui.setValue(next) }
    const shown = ENVS.filter((e) => e.includes(search.trim()))

    return html`
      <div class="row">
        <label>Branch <input name="branch" value=${c.branch} onInput=${(e) => set({ branch: e.currentTarget.value })} /></label>
        <label>Filter <input name="search" type="search" value=${search} onInput=${(e) => setSearch(e.currentTarget.value)} /></label>
        <button type="button" name="select-all" onClick=${() => set({ envs: [...new Set([...c.envs, ...shown])] })}>Select shown</button>
        <button type="button" name="select-none" onClick=${() => set({ envs: c.envs.filter((e) => !shown.includes(e)) })}>Clear shown</button>
      </div>
      ${error && html`<p role="alert">Could not load build history: ${error}</p>`}
      <div class="grid">${shown.map((env) => {
        const last = lastByEnv[env]
        return html`<label key=${env} class=${'card' + (c.envs.includes(env) ? ' on' : '')} data-env=${env}>
          <input type="checkbox" name=${env} checked=${c.envs.includes(env)} onChange=${() => set({ envs: toggle(c.envs, env) })} />
          <strong> ${env}</strong>
          <div class="last">${last ? `#${last.number} ${last.result || 'running'} · ${last.config.branch}` : 'no build yet'}</div>
        </label>`
      })}</div>
      <div class="row">Services ${SERVICES.map((s) => html`<label><input type="checkbox" name=${'svc-' + s}
        checked=${c.services.includes(s)} onChange=${() => set({ services: toggle(c.services, s) })} /> ${s}</label>`)}</div>
      <label class="row"><input type="checkbox" name="autoDeploy" checked=${c.autoDeploy}
        onChange=${(e) => set({ autoDeploy: e.currentTarget.checked })} /> Deploy after the build</label>
      <p class="summary">${c.services.length} service(s) from ${c.branch} to ${c.envs.length} environment(s)</p>`
  }

  render(html`<${App} />`, root)
  return () => render(null, root)
}''')
  }
  stages {
    stage('Build and deploy') {
      steps {
        script {
          def deploy = readJSON(text: params.DEPLOY)
          echo "DEPLOY=${params.DEPLOY}"
          echo "Building ${deploy.services} from ${deploy.branch} for ${deploy.envs}"
        }
      }
    }
  }
}
