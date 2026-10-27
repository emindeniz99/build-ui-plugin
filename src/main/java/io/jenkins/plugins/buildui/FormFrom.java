package io.jenkins.plugins.buildui;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.buildui.json.Json;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * {@code uiParameter(formFrom: 'forms/deploy')}: the form's files live in the
 * job's repository and are read from the head of the branch the job builds, so
 * a push shows up on the next page load without a build.
 *
 * <p>The job alone decides the repository, branch and credentials; the path is
 * part of the job's definition. Reading happens on the controller, without a
 * workspace or an agent, through {@link Reader}. One read per job and folder is
 * cached for {@link #CACHE_MILLIS} and shared by the page, the submit check and
 * {@link RefGuard}, so all three see the same version within that window.
 */
public final class FormFrom {

    private static final Logger LOGGER = Logger.getLogger(FormFrom.class.getName());

    static final long CACHE_MILLIS = 10_000;
    static final int CACHE_ENTRIES = 256;
    static final long TIMEOUT_MILLIS = 20_000;
    static final int MAX_PATH_CHARS = 200;
    /** For form.mjs, form.html and form.css; JSON files use the JSON limit. */
    static final int MAX_CODE_BYTES = 512 * 1024;
    static final int MAX_JSON_BYTES = Json.MAX_CHARS;

    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*");

    static final String UNSUPPORTED_INPUT = "formFrom works on Build with Parameters only; the input step cannot read a form from the repository";

    /** The files of a formFrom folder at one revision; a file that does not exist is null. */
    public record Files(String revision, @CheckForNull String module, @CheckForNull String html, @CheckForNull String css,
            @CheckForNull String schema, @CheckForNull String defaultJson) {}

    /** Reads a formFrom folder of a job's repository; {@link ScmFormReader} implements it when Pipeline and the SCM API are installed. */
    public interface Reader extends ExtensionPoint {
        /** Reads {@code path} at the branch head; throws {@link IllegalArgumentException} with a reason a user can act on. */
        Files read(Job<?, ?> job, String path) throws IOException, InterruptedException;
    }

    private record Cached(long at, @CheckForNull FormCode code, @CheckForNull String error) {}

    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /** Reads in progress, so many viewers of one page start one read. */
    private static final Map<String, CompletableFuture<Cached>> READING = new HashMap<>();

    private FormFrom() {}

    /** Checks a formFrom path: relative to the repository root, no {@code ..}, a conservative character set. */
    static String checkPath(String path) {
        if (path.length() > MAX_PATH_CHARS) {
            throw new IllegalArgumentException("formFrom is " + path.length() + " characters; the limit is " + MAX_PATH_CHARS);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("formFrom '" + path + "' must be a folder relative to the repository root:"
                    + " letters, digits, '.', '_' and '-' separated by '/', with no leading or trailing '/' and no '\\'");
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("formFrom '" + path + "' must not contain '" + segment + "'");
            }
        }
        return path;
    }

    /**
     * The form in {@code path} at the head of the job's branch, from the cache
     * when it is younger than {@link #CACHE_MILLIS}. Throws
     * {@link IllegalArgumentException} with the reason when the folder cannot
     * be read; nothing falls back to older content.
     */
    static FormCode read(Job<?, ?> job, String path) {
        // One job is one head, and the reader decides per head which revision it trusts, so an
        // untrusted change request's entry (read at the trusted revision) never serves another job.
        String key = job.getFullName() + "\n" + path;
        synchronized (CACHE) {
            Cached cached = CACHE.get(key);
            if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
                return result(cached);
            }
        }
        CompletableFuture<Cached> reading;
        synchronized (READING) {
            reading = READING.get(key);
            if (reading == null) {
                CompletableFuture<Cached> started = CompletableFuture.supplyAsync(() -> load(key, job, path), Computer.threadPoolForRemoting);
                READING.put(key, started);
                started.whenComplete((entry, error) -> {
                    synchronized (READING) {
                        READING.remove(key, started);
                    }
                });
                reading = started;
            }
        }
        try {
            return result(reading.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            // The read goes on and fills the cache; the next page load may find it there.
            throw new IllegalArgumentException("formFrom: reading '" + path + "' from the repository of " + job.getFullName()
                    + " took longer than " + TIMEOUT_MILLIS / 1000 + " s; try again shortly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("formFrom: interrupted while reading '" + path + "'", e);
        } catch (ExecutionException e) {
            throw new IllegalArgumentException("formFrom: could not read '" + path + "' (" + e.getCause().getClass().getSimpleName() + ")", e);
        }
    }

    private static FormCode result(Cached entry) {
        if (entry.error() != null) {
            throw new IllegalArgumentException(entry.error());
        }
        return entry.code();
    }

    /** Runs on a pool thread, never on a thread that holds the queue lock. */
    private static Cached load(String key, Job<?, ?> job, String path) {
        Cached entry;
        Files files = null;
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            ExtensionList<Reader> readers = ExtensionList.lookup(Reader.class);
            if (readers.isEmpty()) {
                entry = error(job, path, "formFrom needs the Pipeline and SCM API plugins");
            } else {
                files = readers.get(0).read(job, path);
                Files read = files;
                LOGGER.fine(() -> "Read formFrom '" + path + "' of " + job.getFullName() + " at " + read.revision());
                entry = new Cached(System.currentTimeMillis(), FormCode.of(files.module(), files.html(), files.css(),
                        files.schema(), files.defaultJson(), true), null);
            }
        } catch (IllegalArgumentException e) {
            String message = String.valueOf(e.getMessage());
            if (files == null && !message.startsWith("formFrom")) {
                // Not the reader's own reason but one from SCM code, which may quote a remote URL with its token.
                entry = hidden(job, path, e);
            } else {
                // The reader's reason, or what is wrong with the files' content.
                entry = error(job, path, message.startsWith("formFrom") ? message : "formFrom '" + path + "': " + message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry = error(job, path, "formFrom: interrupted while reading '" + path + "'");
        } catch (IOException | RuntimeException e) {
            entry = hidden(job, path, e);
        }
        synchronized (CACHE) {
            CACHE.put(key, entry);
        }
        return entry;
    }

    /** The message reaches the browser: keep it to the job and path, never SCM or credential details. */
    private static Cached hidden(Job<?, ?> job, String path, Exception e) {
        LOGGER.log(Level.WARNING, "Could not read formFrom '" + path + "' of " + job.getFullName(), e);
        return error(job, path, "formFrom: could not read '" + path + "' from the repository of " + job.getFullName()
                + " (" + e.getClass().getSimpleName() + "); the controller log has the details");
    }

    private static Cached error(Job<?, ?> job, String path, String message) {
        LOGGER.fine(() -> job.getFullName() + " formFrom '" + path + "': " + message);
        return new Cached(System.currentTimeMillis(), null, message);
    }

    /** Why a job of this kind cannot read a formFrom folder. */
    static String unsupported(Job<?, ?> job) {
        return "formFrom works in branch jobs of a multibranch Pipeline and in Pipeline jobs whose definition is"
                + " 'Pipeline script from SCM'; " + job.getFullName() + " is neither";
    }

    /** Forgets the reads cached under a job's full name. */
    static void forget(String fullName) {
        String prefix = fullName + "\n";
        synchronized (CACHE) {
            CACHE.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    /**
     * The cache is keyed by full name. Without this, a job that took a renamed
     * or deleted job's name within {@link #CACHE_MILLIS} showed the other
     * job's form, and its values were checked against the other job's schema.
     * Jenkins reports a folder's move for every item inside it too.
     */
    @Extension
    public static final class Renames extends ItemListener {
        @Override
        public void onLocationChanged(Item item, String oldFullName, String newFullName) {
            forget(oldFullName);
            forget(newFullName);
        }

        @Override
        public void onDeleted(Item item) {
            forget(item.getFullName());
        }
    }

    /** Forgets every read, as an expired cache does. For tests. */
    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    static int cacheSize() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    /** Puts an entry without reading, to test the bound. For tests. */
    static void remember(String key) {
        synchronized (CACHE) {
            CACHE.put(key, new Cached(System.currentTimeMillis(), null, "test"));
        }
    }
}
