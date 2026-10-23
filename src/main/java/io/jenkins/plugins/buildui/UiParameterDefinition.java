package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.buildui.json.Json;
import io.jenkins.plugins.buildui.json.Schema;
import java.util.List;
import java.util.Objects;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * A build parameter whose form the Jenkinsfile writes in HTML, CSS and a
 * browser-native ES module. The value is one JSON document, validated on the
 * controller against {@link #getSchema()} on every path that creates it.
 */
public class UiParameterDefinition extends SimpleParameterDefinition {

    private static final long serialVersionUID = 1L;

    private String runtime = Runtimes.PREACT;
    private String module;
    private String html;
    private String css;
    private String schema;
    private String defaultJson;

    private transient Schema compiled;

    @DataBoundConstructor
    public UiParameterDefinition(@NonNull String name) {
        super(name);
    }

    @NonNull
    public String getRuntime() {
        return runtime;
    }

    @DataBoundSetter
    public void setRuntime(@CheckForNull String runtime) {
        this.runtime = Runtimes.check(Util.fixEmptyAndTrim(runtime) == null ? Runtimes.PREACT : runtime.trim());
    }

    @CheckForNull
    public String getModule() {
        return module;
    }

    @DataBoundSetter
    public void setModule(@CheckForNull String module) {
        this.module = Util.fixEmptyAndTrim(module) == null ? null : module;
    }

    @CheckForNull
    public String getHtml() {
        return html;
    }

    @DataBoundSetter
    public void setHtml(@CheckForNull String html) {
        this.html = Util.fixEmptyAndTrim(html) == null ? null : html;
    }

    @CheckForNull
    public String getCss() {
        return css;
    }

    @DataBoundSetter
    public void setCss(@CheckForNull String css) {
        this.css = Util.fixEmptyAndTrim(css) == null ? null : css;
    }

    @CheckForNull
    public String getSchema() {
        return schema;
    }

    @DataBoundSetter
    public void setSchema(@CheckForNull String schema) {
        String s = Util.fixEmptyAndTrim(schema);
        this.compiled = s == null ? null : Schema.compile(Json.parse(s, "schema"));
        this.schema = s;
    }

    @CheckForNull
    public String getDefaultJson() {
        return defaultJson;
    }

    @DataBoundSetter
    public void setDefaultJson(@CheckForNull String defaultJson) {
        String d = Util.fixEmptyAndTrim(defaultJson);
        if (d != null) {
            Json.parse(d, "defaultJson");
        }
        this.defaultJson = d;
    }

    /** Errors of {@code json} against the schema; empty when it is valid. */
    public List<String> errorsFor(String json) {
        JsonNode node;
        try {
            node = Json.parse(json, "value of " + getName());
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        Schema s = compiledSchema();
        return s == null ? List.of() : s.validate(node);
    }

    @CheckForNull
    Schema compiledSchema() {
        if (compiled == null && schema != null) {
            compiled = Schema.compile(Json.parse(schema, "schema"));
        }
        return compiled;
    }

    @Override
    public boolean isValid(ParameterValue value) {
        return value instanceof StringParameterValue s && s.getValue() != null && errorsFor(s.getValue()).isEmpty();
    }

    @Override
    public ParameterValue createValue(String value) {
        List<String> errors = errorsFor(value);
        if (!errors.isEmpty()) {
            throw new InvalidValueException("Invalid value for parameter '" + getName() + "': " + String.join("; ", errors));
        }
        return new UiParameterValue(getName(), Json.write(Json.parse(value, "value")), getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
        Object value = jo.get("value");
        if (value == null) {
            throw new InvalidValueException("No value for parameter '" + getName() + "'");
        }
        return createValue(value.toString());
    }

    @Override
    public UiParameterValue getDefaultParameterValue() {
        return (UiParameterValue) createValue(defaultJson == null ? "{}" : defaultJson);
    }

    @Override
    public ParameterDefinition copyWithDefaultValue(ParameterValue value) {
        if (!(value instanceof StringParameterValue s) || s.getValue() == null) {
            return this;
        }
        String json = s.getValue();
        UiParameterDefinition copy = new UiParameterDefinition(getName());
        copy.setDescription(getDescription());
        copy.runtime = runtime;
        copy.module = module;
        copy.html = html;
        copy.css = css;
        copy.setSchema(schema);
        copy.setDefaultJson(json);
        return copy;
    }

    /** Full name of the job whose page renders this form, for {@code ui.job}; empty outside a job. */
    public String getJobFullName() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        Job<?, ?> job = req == null ? null : req.findAncestorObject(Job.class);
        return job == null ? "" : job.getFullName();
    }

    /** The form's initial JSON: the default, or {@code {}}. Used by the view. */
    public String getInitialJson() {
        return defaultJson == null ? "{}" : defaultJson;
    }

    /** Content keys for the view; registering here keeps the store filled after a restart. */
    @CheckForNull
    public String getModuleKey() {
        return module == null ? null : ContentStore.put(module, "mjs", "text/javascript;charset=UTF-8");
    }

    @CheckForNull
    public String getCssKey() {
        return css == null ? null : ContentStore.put(css, "css", "text/css;charset=UTF-8");
    }

    @CheckForNull
    public String getHtmlKey() {
        return html == null ? null : ContentStore.put(html, "html.txt", "text/plain;charset=UTF-8");
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getDescription(), runtime, module, html, css, schema, defaultJson);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof UiParameterDefinition o
                && obj.getClass() == getClass()
                && Objects.equals(getName(), o.getName())
                && Objects.equals(getDescription(), o.getDescription())
                && Objects.equals(runtime, o.runtime)
                && Objects.equals(module, o.module)
                && Objects.equals(html, o.html)
                && Objects.equals(css, o.css)
                && Objects.equals(schema, o.schema)
                && Objects.equals(defaultJson, o.defaultJson);
    }

    @Extension
    @Symbol("uiParameter")
    public static class DescriptorImpl extends ParameterDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "UI form (build-ui)";
        }

        public ListBoxModel doFillRuntimeItems() {
            ListBoxModel items = new ListBoxModel();
            Runtimes.ALL.forEach(items::add);
            return items;
        }

        @POST
        public FormValidation doCheckSchema(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.ok();
            }
            try {
                Schema.compile(Json.parse(value, "schema"));
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckDefaultJson(@QueryParameter String value, @QueryParameter String schema) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.ok();
            }
            try {
                JsonNode node = Json.parse(value, "defaultJson");
                if (Util.fixEmptyAndTrim(schema) != null) {
                    List<String> errors = Schema.compile(Json.parse(schema, "schema")).validate(node);
                    if (!errors.isEmpty()) {
                        return FormValidation.error(String.join("; ", errors));
                    }
                }
                return FormValidation.ok();
            } catch (IllegalArgumentException e) {
                return FormValidation.error(e.getMessage());
            }
        }
    }
}
