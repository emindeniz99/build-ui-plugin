package io.jenkins.plugins.buildui;

import hudson.model.InvisibleAction;
import hudson.model.Job;

/** A panel on a build's page; {@code summary.jelly} renders it with the shared host. */
public class UiPanelAction extends InvisibleAction {

    private final String title;
    private final String runtime;
    private final String module;
    private final String html;
    private final String css;
    private final String data;
    private final boolean sandbox;

    UiPanelAction(String title, String runtime, String module, String html, String css, String data, boolean sandbox) {
        this.title = title;
        this.runtime = runtime;
        this.module = module;
        this.html = html;
        this.css = css;
        this.data = data;
        this.sandbox = sandbox;
    }

    public String getTitle() {
        return title;
    }

    public String getRuntime() {
        return runtime;
    }

    /** build.xml is read without the step's check; see {@link UiParameterDefinition#readResolve()}. */
    protected Object readResolve() {
        String checked = Runtimes.orDefault(runtime, "panel " + title);
        return checked.equals(runtime) ? this : new UiPanelAction(title, checked, module, html, css, data, sandbox);
    }

    public String getData() {
        return data;
    }

    /** Full name of the job whose build shows this panel, for {@code ui.job}. Used by the view. */
    public String getJobFullName() {
        Job<?, ?> job = UiParameterDefinition.requestJob();
        return job == null ? "" : job.getFullName();
    }

    /** Whether the runtime compiles templates with eval; host.js then checks that CSP allows eval. */
    public boolean isNeedsEval() {
        return Runtimes.needsEval(runtime);
    }

    /** False when the runtime needs eval and an admin has not allowed such runtimes. */
    public boolean isRuntimeAllowed() {
        return Runtimes.allowed(runtime);
    }

    /** True when this panel's code runs in a sandboxed iframe. */
    public boolean isSandboxed() {
        return SandboxAction.applies(sandbox, module, html, css);
    }

    /** False only when an admin requires approval and this panel's code has none yet. */
    public boolean isApproved() {
        return FormApproval.allowed(runtime, module, html, css, isSandboxed(), UiParameterDefinition.requestJob(), "panel:" + title);
    }

    public String getModuleKey() {
        return module == null ? null : ContentStore.put(module, "mjs", "text/javascript;charset=UTF-8");
    }

    public String getCssKey() {
        return css == null ? null : ContentStore.put(css, "css", "text/css;charset=UTF-8");
    }

    public String getHtmlKey() {
        return html == null ? null : ContentStore.put(html, "html.txt", "text/plain;charset=UTF-8");
    }
}
