package io.jenkins.plugins.buildui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.buildui.json.Json;
import jenkins.model.Jenkins;

/**
 * {@code ui.query('jenkinsRuns', { job: 'folder/name', limit: 20, parameter: 'NAME' })}:
 * the latest builds of a job the user can read, newest first. Other build
 * parameters are left out on purpose, since they can hold secrets; with
 * {@code parameter}, each run carries the parsed JSON of that build-ui
 * parameter as {@code config}, which Jenkins already shows to readers on the
 * build's Parameters page. That gives forms structured history instead of
 * parsed build descriptions.
 */
@Extension
public class JenkinsRunsProvider extends DataProvider {

    static final int MAX_LIMIT = 100;

    @Override
    public String getId() {
        return "jenkinsRuns";
    }

    @Override
    public JsonNode query(JsonNode args) {
        String name = args.path("job").asText("");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("jenkinsRuns needs a 'job' argument");
        }
        int limit = Math.min(Math.max(args.path("limit").asInt(20), 1), MAX_LIMIT);
        String parameter = args.path("parameter").asText("");
        Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("no job '" + name + "' that you can read");
        }
        ArrayNode runs = JsonNodeFactory.instance.arrayNode();
        for (Run<?, ?> run : job.getBuilds().limit(limit)) {
            ObjectNode o = runs.addObject();
            o.put("number", run.getNumber());
            o.put("displayName", run.getDisplayName());
            o.put("description", run.getDescription());
            Result result = run.getResult();
            o.put("result", result == null ? null : result.toString());
            o.put("building", run.isBuilding());
            o.put("timestamp", run.getTimeInMillis());
            o.put("duration", run.getDuration());
            o.put("url", run.getUrl());
            if (!parameter.isEmpty()) {
                ParametersAction params = run.getAction(ParametersAction.class);
                ParameterValue value = params == null ? null : params.getParameter(parameter);
                if (value instanceof UiParameterValue ui && ui.getValue() != null) {
                    o.set("config", Json.parse(ui.getValue(), "config"));
                } else {
                    o.putNull("config");
                }
            }
        }
        return runs;
    }
}
