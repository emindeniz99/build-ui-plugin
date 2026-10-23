package io.jenkins.plugins.buildui;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterValue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Checks the {@code x-git-ref} values of every queued build before it starts,
 * whatever queued it (a {@code build} step, a timer, a script). Listing refs
 * contacts the Git server and can take minutes, while Jenkins asks queue
 * extensions with the lock for the whole queue held. So the item waits,
 * blocked, while a pool thread lists the refs; a ref that does not exist, or
 * refs that cannot be listed, cancel the item before it starts.
 */
@Extension
public class RefGuard extends QueueTaskDispatcher {

    private static final Logger LOGGER = Logger.getLogger(RefGuard.class.getName());

    /** Queue item ID to the errors its check found; removed when the item leaves the queue. */
    private static final Map<Long, CompletableFuture<List<String>>> CHECKS = new ConcurrentHashMap<>();

    private static final CauseOfBlockage CHECKING = new CauseOfBlockage() {
        @Override
        public String getShortDescription() {
            return "Checking that the Git branches and tags in its parameters exist";
        }
    };

    @Override
    public CauseOfBlockage canRun(Queue.Item item) {
        if (!(item.task instanceof Job<?, ?> job)) {
            return null;
        }
        Map<UiParameterDefinition, String> values = refValues(job, item);
        if (values.isEmpty()) {
            return null;
        }
        long id = item.getId();
        CompletableFuture<List<String>> check = CHECKS.computeIfAbsent(id, key -> {
            CompletableFuture<List<String>> f = CompletableFuture.supplyAsync(() -> check(id, job, values), Computer.threadPoolForRemoting);
            // Only after the future is done, or the next pass would still see it running.
            f.thenRun(() -> Queue.getInstance().scheduleMaintenance());
            return f;
        });
        List<String> errors = check.getNow(null);
        return errors == null || !errors.isEmpty() ? CHECKING : null;
    }

    private static Map<UiParameterDefinition, String> refValues(Job<?, ?> job, Queue.Item item) {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property == null) {
            return Map.of();
        }
        Map<UiParameterDefinition, String> values = new LinkedHashMap<>();
        for (ParametersAction parameters : item.getActions(ParametersAction.class)) {
            for (ParameterValue value : parameters.getAllParameters()) {
                if (property.getParameterDefinition(value.getName()) instanceof UiParameterDefinition ui && ui.hasRefChecks()
                        && value instanceof StringParameterValue s && s.getValue() != null) {
                    values.put(ui, s.getValue());
                }
            }
        }
        return values;
    }

    /** Runs on a pool thread, never under the queue lock. */
    private static List<String> check(long id, Job<?, ?> job, Map<UiParameterDefinition, String> values) {
        List<String> errors = new ArrayList<>();
        try {
            values.forEach((ui, json) -> ui.refErrorsFor(json, job).forEach(e -> errors.add(ui.getName() + ": " + e)));
        } catch (RuntimeException e) {
            errors.add("could not check Git refs (" + e.getMessage() + ")");
        }
        if (!errors.isEmpty()) {
            LOGGER.warning(() -> "Cancelling a queued build of " + job.getFullName() + ": " + String.join("; ", errors));
            Queue queue = Queue.getInstance();
            queue.withLock(() -> {
                Queue.Item current = queue.getItem(id);
                if (current != null) {
                    queue.cancel(current);
                }
            });
        }
        return errors;
    }

    @Extension
    public static final class Cleanup extends QueueListener {
        @Override
        public void onLeft(Queue.LeftItem item) {
            CHECKS.remove(item.getId());
        }
    }
}
