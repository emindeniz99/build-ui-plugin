package io.jenkins.plugins.buildui;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterValue;
import java.util.List;
import java.util.logging.Logger;

/**
 * Last line of defence: refuses to queue a build whose UI parameter value
 * fails its schema, however the value was made (a script, another plugin,
 * a hand-built {@link ParametersAction}). The form, REST and {@code build}
 * paths already reject invalid values with a message before they get here.
 */
@Extension
public class QueueGuard extends Queue.QueueDecisionHandler {

    private static final Logger LOGGER = Logger.getLogger(QueueGuard.class.getName());

    @Override
    public boolean shouldSchedule(Queue.Task task, List<Action> actions) {
        if (!(task instanceof Job<?, ?> job)) {
            return true;
        }
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return true;
        }
        for (Action action : actions) {
            if (!(action instanceof ParametersAction parameters)) {
                continue;
            }
            for (ParameterValue value : parameters.getAllParameters()) {
                ParameterDefinition definition = property.getParameterDefinition(value.getName());
                if (!(definition instanceof UiParameterDefinition ui)) {
                    continue;
                }
                List<String> errors = value instanceof StringParameterValue s && s.getValue() != null
                        ? ui.errorsFor(s.getValue(), job)
                        : List.of("value is not a JSON string but " + value.getClass().getSimpleName());
                if (!errors.isEmpty()) {
                    LOGGER.warning(() -> "Not scheduling " + job.getFullName() + ": parameter " + value.getName() + ": " + String.join("; ", errors));
                    return false;
                }
            }
        }
        return true;
    }
}
