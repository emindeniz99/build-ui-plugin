package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Run;
import io.jenkins.plugins.buildui.json.Json;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * {@code uiPanel(title: '…', module: '…', data: '{…}')} adds a panel to the
 * build's page, drawn by the same {@code mount()} contract as the form. The
 * module reads {@code ui.value}, the JSON in {@code data}.
 */
public class UiPanelStep extends Step {

    private final String title;
    private String runtime = Runtimes.PREACT;
    private String module;
    private String html;
    private String css;
    private String data = "{}";
    private boolean sandbox;

    @DataBoundConstructor
    public UiPanelStep(@NonNull String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public String getRuntime() {
        return runtime;
    }

    @DataBoundSetter
    public void setRuntime(String runtime) {
        this.runtime = Runtimes.check(runtime);
    }

    public String getModule() {
        return module;
    }

    @DataBoundSetter
    public void setModule(@CheckForNull String module) {
        this.module = Util.fixEmptyAndTrim(module) == null ? null : module;
    }

    public String getHtml() {
        return html;
    }

    @DataBoundSetter
    public void setHtml(@CheckForNull String html) {
        this.html = Util.fixEmptyAndTrim(html) == null ? null : html;
    }

    public String getCss() {
        return css;
    }

    @DataBoundSetter
    public void setCss(@CheckForNull String css) {
        this.css = Util.fixEmptyAndTrim(css) == null ? null : css;
    }

    public String getData() {
        return data;
    }

    @DataBoundSetter
    public void setData(@CheckForNull String data) {
        this.data = Util.fixEmptyAndTrim(data) == null ? "{}" : Json.write(Json.parse(data, "data"));
    }

    public boolean isSandbox() {
        return sandbox;
    }

    /** Runs the panel's code in a sandboxed iframe, as {@code uiParameter(sandbox: true)} does. */
    @DataBoundSetter
    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, new UiPanelAction(title, runtime, module, html, css, data, sandbox));
    }

    private static final class Execution extends SynchronousStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private final transient UiPanelAction action;

        Execution(StepContext context, UiPanelAction action) {
            super(context);
            this.action = action;
        }

        @Override
        protected Void run() throws Exception {
            Run<?, ?> run = getContext().get(Run.class);
            run.addAction(action);
            run.save();
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "uiPanel";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Add a build-ui panel to the build page";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class);
        }
    }
}
