package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.buildui.json.Json;
import io.jenkins.plugins.buildui.json.Schema;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
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
    private static final Logger LOGGER = Logger.getLogger(UiParameterDefinition.class.getName());

    private String runtime = Runtimes.PREACT;
    private String module;
    private String html;
    private String css;
    private String schema;
    private String defaultJson;
    private boolean sandbox;
    private String formFrom;

    /** The inline fields after the HTML is split; computed when first used. */
    private transient FormCode inline;
    /** Why a definition loaded from config.xml cannot be used; the setters would have refused it. */
    private transient String configError;
    /** The job whose parameters hold this definition, found when a path without a request needs it. */
    private transient Job<?, ?> owner;

    @DataBoundConstructor
    public UiParameterDefinition(@NonNull String name) {
        super(name);
    }

    @NonNull
    public String getRuntime() {
        return runtime;
    }

    /**
     * XStream (config.xml from REST or the CLI) sets fields without the setter.
     * host.js imports {@code runtimes/<id>.js}, so an unchecked ID could load any
     * same-origin script; an unknown one falls back to the default. A bad
     * {@code formFrom} cannot be thrown here (XStream would drop the element
     * silently), so it is kept as an error the form and every value check show.
     */
    protected Object readResolve() {
        runtime = Runtimes.orDefault(runtime, "parameter " + getName());
        if (formFrom != null) {
            try {
                FormFrom.checkPath(formFrom);
                rejectInlineFields();
            } catch (IllegalArgumentException e) {
                configError = e.getMessage();
                LOGGER.warning(() -> "Parameter " + getName() + ": " + configError);
            }
        }
        return this;
    }

    /** The repository folder the form is read from, or null when the Jenkinsfile holds the form. */
    @CheckForNull
    public String getFormFrom() {
        return formFrom;
    }

    /**
     * The folder holds {@code form.mjs}, {@code form.html}, {@code form.css},
     * {@code schema.json} and {@code default.json}, all optional, so the inline
     * fields for them must stay empty: two sources for one part would leave
     * readers guessing which one the form uses.
     */
    @DataBoundSetter
    public void setFormFrom(@CheckForNull String formFrom) {
        String f = Util.fixEmptyAndTrim(formFrom);
        if (f != null) {
            FormFrom.checkPath(f);
        }
        String old = this.formFrom;
        this.formFrom = f;
        try {
            rejectInlineFields();
        } catch (IllegalArgumentException e) {
            this.formFrom = old;
            throw e;
        }
    }

    private void rejectInlineFields() {
        if (formFrom == null) {
            return;
        }
        List<String> set = new ArrayList<>();
        if (module != null) set.add("module");
        if (html != null) set.add("html");
        if (css != null) set.add("css");
        if (schema != null) set.add("schema");
        if (defaultJson != null) set.add("defaultJson");
        if (!set.isEmpty()) {
            throw new IllegalArgumentException("parameter " + getName() + ": formFrom reads module, html, css, schema and defaultJson from '"
                    + formFrom + "', so the Jenkinsfile must not set them too; remove " + String.join(", ", set));
        }
    }

    @DataBoundSetter
    public void setRuntime(@CheckForNull String runtime) {
        this.runtime = Runtimes.check(Util.fixEmptyAndTrim(runtime) == null ? Runtimes.PREACT : runtime.trim());
    }

    @CheckForNull
    public String getModule() {
        return module;
    }

    /**
     * The inline setters check what they can when the definition is made, so a
     * Jenkinsfile mistake fails the build that defines the form: each one
     * builds the inline form with its new value and keeps it only if it holds.
     */
    @DataBoundSetter
    public void setModule(@CheckForNull String module) {
        String old = this.module;
        this.module = Util.fixEmptyAndTrim(module) == null ? null : module;
        recheck(() -> this.module = old);
    }

    @CheckForNull
    public String getHtml() {
        return html;
    }

    /** May hold the whole form: a {@code <script type="module">} and {@code <style>} elements are split out ({@link SingleFileHtml}). */
    @DataBoundSetter
    public void setHtml(@CheckForNull String html) {
        String old = this.html;
        this.html = Util.fixEmptyAndTrim(html) == null ? null : html;
        recheck(() -> this.html = old);
    }

    @CheckForNull
    public String getCss() {
        return css;
    }

    @DataBoundSetter
    public void setCss(@CheckForNull String css) {
        String old = this.css;
        this.css = Util.fixEmptyAndTrim(css) == null ? null : css;
        recheck(() -> this.css = old);
    }

    @CheckForNull
    public String getSchema() {
        return schema;
    }

    @DataBoundSetter
    public void setSchema(@CheckForNull String schema) {
        String old = this.schema;
        this.schema = Util.fixEmptyAndTrim(schema);
        recheck(() -> this.schema = old);
    }

    @CheckForNull
    public String getDefaultJson() {
        return defaultJson;
    }

    @DataBoundSetter
    public void setDefaultJson(@CheckForNull String defaultJson) {
        String old = this.defaultJson;
        this.defaultJson = Util.fixEmptyAndTrim(defaultJson);
        recheck(() -> this.defaultJson = old);
    }

    /** Validates the fields after a setter changed one; on failure {@code undo} restores it and the error propagates. */
    private void recheck(Runnable undo) {
        try {
            rejectInlineFields();
            inline = null;
            inline = inlineCode();
        } catch (IllegalArgumentException e) {
            undo.run();
            inline = null;
            throw e;
        }
    }

    private FormCode inlineCode() {
        FormCode c = inline;
        if (c == null) {
            c = FormCode.of(module, html, css, schema, defaultJson, false);
            inline = c;
        }
        return c;
    }

    /** Whether the form asks to run its code in a sandboxed iframe; see {@link #isSandboxed()}. */
    public boolean isSandbox() {
        return sandbox;
    }

    @DataBoundSetter
    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    /** True when this form's code runs in a sandboxed iframe: it asks for it, or an admin sandboxes all forms. */
    public boolean isSandboxed() {
        FormCode c = viewCodeOrNull();
        return c != null && SandboxAction.applies(sandbox, c.module(), c.html(), c.css());
    }

    /**
     * The form's code and data: the inline fields, or the formFrom folder at the
     * head of {@code job}'s branch (cached briefly, see {@link FormFrom}).
     * Throws {@link IllegalArgumentException} when it cannot be had.
     */
    FormCode code(@CheckForNull Job<?, ?> job) {
        if (configError != null) {
            throw new IllegalArgumentException(configError);
        }
        if (formFrom == null) {
            return inlineCode();
        }
        if (job == null) {
            throw new IllegalArgumentException("formFrom: no job to read '" + formFrom + "' for");
        }
        return FormFrom.read(job, formFrom);
    }

    /**
     * The code a page shows, read once per request so that one page shows one
     * version even if the cache expires while it renders. The input step has
     * no branch head to read, so formFrom is refused there.
     */
    private FormCode requestCode() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (formFrom == null || req == null) {
            return code(req == null ? null : req.findAncestorObject(Job.class));
        }
        if (req.findAncestorObject(Run.class) != null) {
            throw new IllegalArgumentException(FormFrom.UNSUPPORTED_INPUT);
        }
        String key = "build-ui.code." + System.identityHashCode(this);
        Object memo = req.getAttribute(key);
        if (memo instanceof FormCode c) {
            return c;
        }
        if (memo instanceof String error) {
            throw new IllegalArgumentException(error);
        }
        try {
            FormCode c = code(req.findAncestorObject(Job.class));
            req.setAttribute(key, c);
            return c;
        } catch (IllegalArgumentException e) {
            req.setAttribute(key, e.getMessage());
            throw e;
        }
    }

    @CheckForNull
    private FormCode viewCodeOrNull() {
        try {
            return requestCode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Why the form cannot be shown (formFrom unreadable, unsupported job), or null. The view then shows the JSON field. */
    @CheckForNull
    public String getFormError() {
        try {
            requestCode();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Errors of {@code json} against the schema; empty when it is valid. */
    public List<String> errorsFor(String json) {
        return errorsFor(json, null);
    }

    /**
     * Schema errors of {@code json}, plus {@code x-git-ref} errors when the job
     * is known: such a string must name a branch or tag of the job's Git
     * repositories. A job that has not checked out yet cannot be asked, so
     * its refs are not checked. Without a job, a formFrom value is only checked
     * to be JSON: reading the schema needs the job, and {@link RefGuard} checks
     * the value against it before the build starts.
     */
    public List<String> errorsFor(String json, @CheckForNull Job<?, ?> job) {
        JsonNode node;
        try {
            node = Json.parse(json, "value of " + getName());
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        if (formFrom != null && job == null && configError == null) {
            return List.of();
        }
        Schema s;
        try {
            s = code(job).schema();
        } catch (IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        if (s == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>(s.validate(node));
        if (errors.isEmpty() && job != null) {
            checkRefs(s.node(), node, "", job, errors);
        }
        return errors;
    }

    /**
     * Whether a queued value needs a check that may contact the repository, which
     * {@link RefGuard} runs off the queue lock: refs for {@code x-git-ref}, or the
     * whole schema of a formFrom folder.
     */
    boolean needsQueueCheck() {
        if (formFrom != null) {
            return true;
        }
        try {
            Schema s = inlineCode().schema();
            return s != null && s.node().findValue("x-git-ref") != null;
        } catch (IllegalArgumentException e) {
            return false; // QueueGuard refuses values of a schema that does not compile
        }
    }

    /** The errors only {@link RefGuard} can find; contacts the repository unless the answer is cached. */
    List<String> queueErrorsFor(String json, Job<?, ?> job) {
        if (formFrom != null) {
            return errorsFor(json, job);
        }
        Schema s = inlineCode().schema();
        List<String> errors = new ArrayList<>();
        if (s != null) {
            checkRefs(s.node(), Json.parse(json, "value of " + getName()), "", job, errors);
        }
        return errors;
    }

    private static void checkRefs(JsonNode schema, JsonNode value, String at, Job<?, ?> job, List<String> errors) {
        if (!schema.isObject()) {
            return;
        }
        JsonNode ref = schema.get("x-git-ref");
        if (ref != null && value.isTextual()) {
            String kind = ref.isTextual() ? ref.asText() : "any";
            JsonNode repo = schema.get("x-git-repository");
            String repository = repo == null ? null : repo.asText();
            RefLookup lookup = RefLookup.find();
            if (lookup == null) {
                errors.add((at.isEmpty() ? "/" : at) + ": x-git-ref needs the Git plugin");
            } else {
                try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                    if (!lookup.hasRef(job, value.asText(), kind, repository)) {
                        errors.add((at.isEmpty() ? "/" : at) + ": '" + value.asText() + "' is not a "
                                + (kind.equals("any") ? "branch or tag" : kind) + " of " + job.getFullName() + "'s repository"
                                + (repository == null ? "" : " matching '" + repository + "'"));
                    }
                } catch (NoRepositoryYetException e) {
                    // Nothing to check against before the first checkout.
                } catch (IllegalArgumentException e) {
                    errors.add((at.isEmpty() ? "/" : at) + ": " + e.getMessage());
                }
            }
        }
        JsonNode props = schema.path("properties");
        JsonNode extra = schema.get("additionalProperties");
        if (value.isObject()) {
            value.fields().forEachRemaining(e -> {
                if (props.has(e.getKey())) {
                    checkRefs(props.get(e.getKey()), e.getValue(), at + "/" + e.getKey(), job, errors);
                } else if (extra != null) {
                    checkRefs(extra, e.getValue(), at + "/" + e.getKey(), job, errors);
                }
            });
        }
        if (value.isArray() && schema.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                checkRefs(schema.get("items"), value.get(i), at + "/" + i, job, errors);
            }
        }
    }

    @CheckForNull
    static Job<?, ?> requestJob() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        return req == null ? null : req.findAncestorObject(Job.class);
    }

    @Override
    public boolean isValid(ParameterValue value) {
        return value instanceof StringParameterValue s && s.getValue() != null && errorsFor(s.getValue()).isEmpty();
    }

    @Override
    public ParameterValue createValue(String value) {
        return create(value, requestJob());
    }

    private UiParameterValue create(String value, @CheckForNull Job<?, ?> job) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (formFrom != null && req != null && req.findAncestorObject(Run.class) != null) {
            throw new InvalidValueException("Invalid value for parameter '" + getName() + "': " + FormFrom.UNSUPPORTED_INPUT);
        }
        List<String> errors = errorsFor(value, job);
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

    /**
     * Not validated here: the REST API exports this getter, and a default that
     * fails a changed schema must not drop the parameter from the job's API.
     * {@link QueueGuard} refuses every build that would use it.
     *
     * <p>A formFrom default comes from {@code default.json} at the branch head.
     * When that cannot be read this throws, so the build that wanted the
     * default fails with the reason instead of starting with something else.
     * Paths without a request (timers, the {@code build} step, {@code params} in
     * the build that defines the parameter) read it on their own thread, bounded
     * by {@link FormFrom#TIMEOUT_MILLIS}. In jenkins-core 2.541.1 only the
     * parameter classes, the CLI's {@code build} command and
     * {@code ParameterizedJobMixIn} (before it queues) call this; no queue class does.
     */
    @Override
    public UiParameterValue getDefaultParameterValue() {
        String json = getInitialJson();
        if (formFrom != null && defaultJson == null) {
            try {
                Job<?, ?> job = requestJob();
                String d = (job != null ? requestCode() : code(ownerJob())).defaultJson();
                json = d == null ? "{}" : d;
            } catch (IllegalArgumentException e) {
                throw new InvalidValueException("No default for parameter '" + getName() + "': " + e.getMessage(), e);
            }
        }
        try {
            json = Json.write(Json.parse(json, "defaultJson"));
        } catch (IllegalArgumentException e) {
            LOGGER.fine(() -> "Default of " + getName() + " is not JSON: " + e.getMessage());
        }
        return new UiParameterValue(getName(), json, getDescription());
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
        copy.sandbox = sandbox;
        if (formFrom == null) {
            copy.setSchema(schema);
            copy.setDefaultJson(json);
        } else {
            // The copy keeps reading the folder; only its default is this value.
            Json.parse(json, "defaultJson");
            copy.formFrom = formFrom;
            copy.defaultJson = Util.fixEmptyAndTrim(json);
        }
        return copy;
    }

    /** The job whose parameters hold this definition; searched once, for paths that have no request. */
    @CheckForNull
    private Job<?, ?> ownerJob() {
        Job<?, ?> known = owner;
        if (known != null && holdsThis(known)) {
            return known;
        }
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
                if (holdsThis(job)) {
                    owner = job;
                    return job;
                }
            }
        }
        return null;
    }

    private boolean holdsThis(Job<?, ?> job) {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        return property != null && property.getParameterDefinitions().stream().anyMatch(d -> d == this);
    }

    /** Full name of the job whose page renders this form, for {@code ui.job}; empty outside a job. */
    public String getJobFullName() {
        Job<?, ?> job = requestJob();
        return job == null ? "" : job.getFullName();
    }

    /** The form's initial JSON: the default, or {@code {}}. Used by the view. */
    public String getInitialJson() {
        if (defaultJson != null || formFrom == null) {
            return defaultJson == null ? "{}" : defaultJson;
        }
        FormCode c = viewCodeOrNull();
        return c == null || c.defaultJson() == null ? "{}" : c.defaultJson();
    }

    /** The schema the page hands to {@code ui.schema}: the inline one, or the folder's schema.json. */
    @CheckForNull
    public String getFormSchema() {
        FormCode c = viewCodeOrNull();
        return c == null ? null : c.schemaText();
    }

    /** Whether the runtime compiles templates with eval; host.js then checks that CSP allows eval. */
    public boolean isNeedsEval() {
        return Runtimes.needsEval(runtime);
    }

    /** False when the runtime needs eval and an admin has not allowed such runtimes. */
    public boolean isRuntimeAllowed() {
        return Runtimes.allowed(runtime);
    }

    /** False only when an admin requires approval and this form's code has none yet. */
    public boolean isApproved() {
        FormCode c = viewCodeOrNull();
        return c != null && FormApproval.allowed(runtime, c.module(), c.html(), c.css(), isSandboxed(), requestJob(), getName());
    }

    /**
     * Content keys for the view; registering here keeps the store filled after
     * a restart. The parts are the split ones, so a module written in the HTML
     * is served like one written in {@code module}.
     */
    @CheckForNull
    public String getModuleKey() {
        FormCode c = viewCodeOrNull();
        return key(c == null ? null : c.module(), "mjs", "text/javascript;charset=UTF-8");
    }

    @CheckForNull
    public String getCssKey() {
        FormCode c = viewCodeOrNull();
        return key(c == null ? null : c.css(), "css", "text/css;charset=UTF-8");
    }

    @CheckForNull
    public String getHtmlKey() {
        FormCode c = viewCodeOrNull();
        return key(c == null ? null : c.html(), "html.txt", "text/plain;charset=UTF-8");
    }

    @CheckForNull
    private static String key(@CheckForNull String text, String extension, String contentType) {
        return text == null ? null : ContentStore.put(text, extension, contentType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getDescription(), runtime, module, html, css, schema, defaultJson, sandbox, formFrom);
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
                && Objects.equals(defaultJson, o.defaultJson)
                && Objects.equals(formFrom, o.formFrom)
                && sandbox == o.sandbox;
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

        /**
         * The caller supplies both the schema and the value, so a check costs
         * controller CPU: only someone who may configure the job runs it.
         */
        private static void checkConfigure(@CheckForNull Item item) {
            if (item == null) {
                Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            } else {
                item.checkPermission(Item.CONFIGURE);
            }
        }

        @POST
        public FormValidation doCheckSchema(@AncestorInPath Item item, @QueryParameter String value) {
            checkConfigure(item);
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
        public FormValidation doCheckDefaultJson(@AncestorInPath Item item, @QueryParameter String value, @QueryParameter String schema) {
            checkConfigure(item);
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
