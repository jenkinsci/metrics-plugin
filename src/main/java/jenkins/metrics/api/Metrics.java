/*
 * The MIT License
 *
 * Copyright (c) 2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.metrics.api;

import com.codahale.metrics.DerivativeGauge;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheck.Result;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.jmx.JmxReporter;
import com.infradna.tool.bridge_method_injector.WithBridgeMethods;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Plugin;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import hudson.triggers.SafeTimerTask;
import hudson.util.PluginServletFilter;
import hudson.util.StreamTaskListener;
import hudson.util.TimeUnit2;
import hudson.util.VersionNumber;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import jenkins.metrics.impl.MetricsFilter;
import jenkins.metrics.impl.ObjectNameFactoryImpl;
import jenkins.metrics.util.HealthChecksThreadPool;
import jenkins.model.Jenkins;
import net.jcip.annotations.ThreadSafe;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Entry point for all things metrics.
 */
public class Metrics extends Plugin {

    /**
     * The frequency with which to run health checks.
     */
    public static final int HEALTH_CHECK_INTERVAL_MINS =
            Integer.getInteger(Metrics.class.getName() + ".HEALTH_CHECK_INTERVAL_MINS", 1);
    /**
     * Permission group for Metrics related permissions.
     */
    public static final PermissionGroup PERMISSIONS =
            new PermissionGroup(Metrics.class, Messages._Metrics_PermissionGroup());
    /**
     * Permission to view the Codahale Metrics Operations Servlet.
     */
    public static final Permission VIEW = new Permission(PERMISSIONS,
            "View", Messages._Metrics_ViewPermission_Description(), Jenkins.ADMINISTER, PermissionScope.JENKINS);
    /**
     * Permission to get a thread dump from the Codahale Metrics Operations Servlet.
     */
    public static final Permission THREAD_DUMP = new Permission(PERMISSIONS,
            "ThreadDump", Messages._Metrics_ThreadDumpPermission_Description(), Jenkins.ADMINISTER, PermissionScope.JENKINS);
    /**
     * Permission to run healthchecks from the Codahale Metrics Operations Servlet.
     */
    public static final Permission HEALTH_CHECK = new Permission(PERMISSIONS,
            "HealthCheck", Messages._Metrics_HealthCheckPermission_Description(), Jenkins.ADMINISTER, PermissionScope.JENKINS);

    /**
     * JMX domain
     */
    public static final String JMX_DOMAIN = "io.jenkins";

    /**
     * Metrics excluded from JMX export
     */
    private static final Pattern JMX_EXCLUSIONS = Pattern.compile("^(vm|system)\\..*|.*\\.(5m|15m|1h|history)$");

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Metrics.class.getName());
    /**
     * Thread pool for running health checks.
     */
    private static ExecutorService threadPoolForHealthChecks;
    /**
     * The registry of metrics.
     */
    private transient final MetricRegistry metricRegistry = new MetricRegistry();
    /**
     * The registry of health checks.
     */
    private transient final HealthCheckRegistry healthCheckRegistry = new HealthCheckRegistry();
    /**
     * The servlet filter.
     */
    private transient final MetricsFilter filter = new MetricsFilter();
    /**
     * Exposes metrics to JMX
     */
    private JmxReporter jmxReporter;

    /**
     * Returns the {@link HealthCheckRegistry} for the current {@link Jenkins}.
     *
     * @return the {@link HealthCheckRegistry} for the current {@link Jenkins}
     * @throws AssertionError if there is no {@link Jenkins} or {@link Metrics} or {@link HealthCheckRegistry}
     */
    @NonNull
    public static HealthCheckRegistry healthCheckRegistry() {
        Metrics plugin = Jenkins.getInstance().getPlugin(Metrics.class);
        if (plugin == null || plugin.healthCheckRegistry == null) {
            throw new AssertionError(Metrics.class.getName() + " is missing its HealthCheckRegistry");
        }
        return plugin.healthCheckRegistry;
    }

    /**
     * Get the last health check results
     *
     * @return a map with health check name -&gt; health check result
     */
    @NonNull
    public static SortedMap<String, Result> getHealthCheckResults() {
        HealthCheckData data = getHealthCheckData();
        return data == null ? new TreeMap<String, Result>() : data.getResults();
    }

    /**
     * Get the current health check data.
     *
     * @return the current health check data or {@code null} if the health checks have not run yet.
     */
    @CheckForNull
    public static HealthCheckData getHealthCheckData() {
        HealthChecker healthChecker = ExtensionList.lookup(PeriodicWork.class).get(HealthChecker.class);
        if (healthChecker == null) {
            LOGGER.warning("Unable to get health check results, HealthChecker is not available");
            return null;
        }
        return healthChecker.getHealthCheckData();
    }

    /**
     * Returns the {@link MetricRegistry} for the current {@link Jenkins}.
     *
     * @return the {@link MetricRegistry} for the current {@link Jenkins}
     * @throws AssertionError if there is no {@link Jenkins} or {@link Metrics} or {@link MetricRegistry}
     */
    @NonNull
    public static MetricRegistry metricRegistry() {
        Metrics plugin = Jenkins.getInstance().getPlugin(Metrics.class);
        if (plugin == null || plugin.metricRegistry == null) {
            throw new AssertionError(Metrics.class.getName() + " is missing its MetricRegistry");
        }
        return plugin.metricRegistry;
    }

    private static MetricsAccessKey.DescriptorImpl accessKeyDescriptorOrDie() {
        MetricsAccessKey.DescriptorImpl descriptor =
                Jenkins.getInstance().getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        return descriptor;
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKey(@CheckForNull String accessKey) {
        accessKeyDescriptorOrDie().checkAccessKey(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyPing(@CheckForNull String accessKey) {
        accessKeyDescriptorOrDie().checkAccessKeyPing(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyThreadDump(@CheckForNull String accessKey) {
        accessKeyDescriptorOrDie().checkAccessKeyThreadDump(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyHealthCheck(@CheckForNull String accessKey) {
        accessKeyDescriptorOrDie().checkAccessKeyHealthCheck(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyMetrics(@CheckForNull String accessKey) {
        accessKeyDescriptorOrDie().checkAccessKeyMetrics(accessKey);
    }

    public static HttpResponse cors(@CheckForNull String accessKey, final HttpResponse resp) {
        return accessKeyDescriptorOrDie().cors(accessKey, resp);
    }

    /**
     * Re-indexes all the access keys from the different {@link MetricsAccessKey.Provider} extensions.
     */
    public static void reindexAccessKeys() {
        accessKeyDescriptorOrDie().reindexAccessKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws Exception {
        PluginServletFilter.addFilter(filter);
        jmxReporter = JmxReporter
                .forRegistry(metricRegistry)
                .inDomain(JMX_DOMAIN)
                .createsObjectNamesWith(new ObjectNameFactoryImpl())
                .filter((name, metric) -> !JMX_EXCLUSIONS.matcher(name).matches())
                .build();
        jmxReporter.start();
    }

    /**
     * Initializes all the metrics providers and health check providers. Ideally we would like this to be called
     * earlier but there are occasional deadlocks that can arise if we attempt to enumerate the extensions prior
     * to {@link InitMilestone#EXTENSIONS_AUGMENTED} so we had to move this functionality out of
     * {@link #postInitialize()}
     */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.JOB_LOADED)
    public static void afterExtensionsAugmented() {
        LOGGER.log(Level.FINER, "Registering metric provider and health check provider extensions...");
        Jenkins jenkins = Jenkins.getInstance();
        Metrics plugin = jenkins.getPlugin(Metrics.class);
        if (plugin == null) {
            LOGGER.log(Level.WARNING, "Could not register metrics providers or health check providers as "
                    + "metrics plugin appears to be disabled");
            return;
        }
        if (plugin.metricRegistry == null || plugin.healthCheckRegistry == null) {
            LOGGER.log(Level.WARNING, "Could not register metrics providers or health check providers as "
                    + "metrics plugin appears have failed initialization");
            return;
        }
        LOGGER.log(Level.FINER, "Confirmed metrics plugin initialized");
        MetricProviderListener.attach(plugin.metricRegistry);
        HealthCheckProviderListener.attach(plugin.healthCheckRegistry);
        threadPoolForHealthChecks = new HealthChecksThreadPool(healthCheckRegistry());
        LOGGER.log(Level.FINE, "Metric provider and health check provider extensions registered");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() throws Exception {
        if (filter != null) {
            PluginServletFilter.removeFilter(filter);
        }
        metricRegistry.removeMatching(MetricFilter.ALL);
        for (String name : healthCheckRegistry.getNames()) {
            healthCheckRegistry.unregister(name);
        }
        if (jmxReporter != null) {
            jmxReporter.stop();
            jmxReporter = null;
        }
    }

    /**
     * provides the health check related metrics.
     *
     * @deprecated use HealthCheckMetricsProvider
     */
    @Deprecated
    @Restricted(NoExternalUse.class)
    public static class HeathCheckMetricsProvider extends HealthCheckMetricsProvider {
    }

    /**
     * provides the health check related metrics.
     */
    @Extension
    public static class HealthCheckMetricsProvider extends MetricProvider {

        /**
         * {@inheritDoc}
         */
        @NonNull
        @Override
        public MetricSet getMetricSet() {
            HealthChecker c = ExtensionList.lookup(PeriodicWork.class).get(HealthChecker.class);
            if (c == null) {
                throw new AssertionError("HealthChecker is missing");
            }
            return metrics(
                    metric(name("jenkins", "health-check", "duration"), c.getHealthCheckDuration()),
                    metric(name("jenkins", "health-check", "count"), c.getHealthCheckCount()),
                    metric(name("jenkins", "health-check", "score"), c.getHealthCheckScore()),
                    metric(name("jenkins", "health-check", "inverse-score"), new DerivativeGauge<Double, Double>(c.getHealthCheckScore()) {
                        @Override
                        protected Double transform(Double value) {
                            return value == null ? null : 1.0 - value;
                        }
                    })
            );
        }
    }

    /**
     * Performs the periodic running of health checks and re-indexing of access keys.
     */
    // TODO switch to AsyncPeriodicWork once on a new enough Jenkins core
    @Extension
    public static class HealthChecker extends PeriodicWork {

        private static final Logger LOGGER = Logger.getLogger(HealthChecker.class.getName());

        /**
         * Timer to track how long the health checks are taking to execute.
         */
        private final Timer healthCheckDuration = new Timer();

        /**
         * The most recent health check data.
         */
        private HealthCheckData healthCheckData = null;

        /**
         * Gauge to track the number of health checks.
         */
        private final Gauge<Integer> healthCheckCount = new Gauge<Integer>() {
            public Integer getValue() {
                return healthCheckRegistry().getNames().size();
            }
        };
        /**
         * Gauge to track the health check score.
         */
        private final Gauge<Double> healthCheckScore = new Gauge<Double>() {
            public Double getValue() {
                return score;
            }
        };
        /**
         * Copy and paste from AsyncPeriodicWork
         */
        private Future<?> future;
        /**
         * The current score.
         */
        private volatile double score = 1.0;
        /**
         * The most recent unhealthy checks.
         */
        private volatile Set<String> lastUnhealthy = null;

        /**
         * Default constructor.
         */
        public HealthChecker() {
            super();
        }

        /**
         * {@inheritDoc}
         */
        public long getRecurrencePeriod() {
            return TimeUnit2.MINUTES.toMillis(Math.min(Math.max(1, HEALTH_CHECK_INTERVAL_MINS),
                    TimeUnit2.DAYS.toMinutes(1)));
        }

        /**
         * Gets the {@link Timer} that tracks how long the health checks are taking to execute.
         *
         * @return the {@link Timer} that tracks how long the health checks are taking to execute.
         */
        public Timer getHealthCheckDuration() {
            return healthCheckDuration;
        }

        /**
         * Gets the most recent results.
         *
         * @return the most recent results.
         * @see #getHealthCheckData()
         */
        @NonNull
        @WithBridgeMethods(Map.class)
        public SortedMap<String, HealthCheck.Result> getHealthCheckResults() {
            return healthCheckData == null ? new TreeMap<String, Result>() : healthCheckData.results;
        }

        /**
         * Gets the most recent health check data (which includes {@link HealthCheckData#getLastModified()})
         *
         * @return the most recent health check data or {@code null} if the health checks have not run yet.
         */
        @CheckForNull
        public HealthCheckData getHealthCheckData() {
            return healthCheckData;
        }

        /**
         * Gets the {@link Gauge} that tracks the number of health checks.
         *
         * @return the {@link Gauge} that tracks the number of health checks.
         */
        public Gauge<Integer> getHealthCheckCount() {
            return healthCheckCount;
        }

        /**
         * Gets the {@link Gauge} that tracks the health check score.
         *
         * @return the {@link Gauge} that tracks the health check score.
         */
        public Gauge<Double> getHealthCheckScore() {
            return healthCheckScore;
        }

        /**
         * Schedules this periodic work now in a new thread, if one isn't already running.
         * Copy and paste from AsyncPeriodicWork
         */
        public final void doRun() {
            try {
                if (future != null && !future.isDone()) {
                    LOGGER.log(Level.INFO,
                            HealthChecker.class.getName() + " thread is still running. Execution aborted.");
                    return;
                }
                if (threadPoolForHealthChecks == null) {
                    Metrics.LOGGER.info("Health checks thread pool not yet initialized, skipping until next execution");
                    return;
                }
                future = threadPoolForHealthChecks.submit(new Runnable() {
                    public void run() {
                        LOGGER.log(Level.FINE, "Started " + HealthChecker.class.getName());
                        long startTime = System.currentTimeMillis();

                        StreamTaskListener l = null;
                        SecurityContext oldContext = ACL.impersonate(ACL.SYSTEM);
                        try {
                            Jenkins jenkins = Jenkins.getInstance();
                            File logFile = getLogFile(jenkins);
                            if (!logFile.isFile()) {
                                File oldFile = new File(jenkins.getRootDir(), HealthChecker.class.getName() + ".log");
                                if (!logFile.getParentFile().isDirectory() && !logFile.getParentFile().mkdirs()) {
                                    LOGGER.log(Level.SEVERE, "Could not create logs directory: {0}",
                                            logFile.getParentFile());
                                }
                                if (oldFile.isFile()) {
                                    if (!oldFile.renameTo(logFile)) {
                                        LOGGER.log(Level.WARNING, "Could not migrate old log file from {0} to {1}",
                                                new Object[]{oldFile, logFile});
                                    }
                                }
                            }
                            l = new StreamTaskListener(logFile);
                            execute(l);
                        } catch (IOException e) {
                            if (l != null) {
                                e.printStackTrace(l.fatalError(e.getMessage()));
                            } else {
                                LOGGER.log(Level.SEVERE,
                                        HealthChecker.class.getName() + " could not create listener", e);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace(l.fatalError("aborted"));
                        } catch (Exception e) {
                            LOGGER.log(Level.SEVERE, "Error running " + HealthChecker.class.getName(), e);
                            if (l != null) {
                                e.printStackTrace(l.fatalError(e.getMessage()));
                            }
                        } finally {
                            if (l != null) {
                                l.closeQuietly();
                            }
                            SecurityContextHolder.setContext(oldContext); // required as we are running in a pool
                        }
                        LOGGER.log(Level.FINE, "Finished " + HealthChecker.class.getName() + ". " +
                                (System.currentTimeMillis() - startTime) + " ms");
                    }
                });
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, HealthChecker.class.getName() + " thread failed with error", t);
            }
        }

        /* package */
        static File getLogFile(Jenkins jenkins) {
            File logsRoot = new File(jenkins.getRootDir(), "logs");
            try {
                // FIXME : remove when 2.114+ See JENKINS-50291
                final Method getLogsRoot = SafeTimerTask.class.getMethod("getLogsRoot");
                logsRoot = (File) getLogsRoot.invoke(null);

            } catch (NoSuchMethodException e) {
                // Expected on < 2.114
            } catch (IllegalAccessException e) {
                e.printStackTrace();
                // Expected on < 2.114
            } catch (InvocationTargetException e) {
                e.printStackTrace();
                // Expected on < 2.114
            }
            return new File(logsRoot, "health-checker.log");
        }

        /**
         * The actual periodic work to run asynchronously.
         *
         * @param listener the listener.
         * @throws IOException          if things go wrong.
         * @throws InterruptedException if interrupted.
         */
        private void execute(TaskListener listener) throws IOException, InterruptedException {
            if (Jenkins.getInstance().getInitLevel().compareTo(InitMilestone.COMPLETED) < 0) {
                // skip health checks until Jenkins is fully started.
                return;
            }
            reindexAccessKeys();
            HealthCheckRegistry registry = healthCheckRegistry();
            // update the active health checks
            Set<String> defined = new HashSet<String>(registry.getNames());
            Set<String> removed = new HashSet<String>(defined);
            for (HealthCheckProvider p : ExtensionList.lookup(HealthCheckProvider.class)) {
                for (Map.Entry<String, HealthCheck> c : p.getHealthChecks().entrySet()) {
                    removed.remove(c.getKey());
                    if (!defined.contains(c.getKey())) {
                        registry.register(c.getKey(), c.getValue());
                        defined.add(c.getKey());
                    }
                }
            }
            for (String key : removed) {
                registry.unregister(key);
            }

            listener.getLogger().println("Starting health checks at " + new Date());
            Timer.Context context = healthCheckDuration.time();
            SortedMap<String, HealthCheck.Result> results;
            try {
                results = registry.runHealthChecks(threadPoolForHealthChecks);
            } catch (RejectedExecutionException e) {
                // should never happen, as we are using a DiscardOldestPolicy in the thread pool queue
                listener.error("Health checks execution was rejected instead of queued: {0}", e);
                LOGGER.log(Level.WARNING, "Health checks execution was rejected instead of queued: {0}", e);
                return;
            } finally {
                context.stop();
            }
            healthCheckData = new HealthCheckData(results, getRecurrencePeriod());
            listener.getLogger().println("Health check results at " + new Date() + ":");
            Set<String> unhealthy = null;
            Set<String> unhealthyName = null;
            int count = 0;
            int total = 0;
            for (Map.Entry<String, HealthCheck.Result> e : results.entrySet()) {
                count++;
                listener.getLogger().println(" * " + e.getKey() + ": " + e.getValue());
                if (e.getValue().isHealthy()) {
                    total++;
                } else {
                    if (unhealthy == null) {
                        unhealthy = new TreeSet<String>();
                        unhealthyName = new TreeSet<String>();
                    }
                    unhealthy.add(e.getKey() + " : " + e.getValue().getMessage());
                    unhealthyName.add(e.getKey());
                }
            }
            // delete any result whose health check had been removed

            score = total / ((double) count);
            Set<String> lastUnhealthy = this.lastUnhealthy;
            this.lastUnhealthy = unhealthyName;
            if (unhealthy != null) {
                if (lastUnhealthy == null || lastUnhealthy.size() < unhealthyName.size() || !lastUnhealthy.equals(unhealthyName)) {
                    LOGGER.log(Level.WARNING, "Some health checks are reporting as unhealthy: {0}", unhealthy);
                } else if (lastUnhealthy.equals(unhealthyName)) {
                    LOGGER.log(Level.FINE, "Some health checks are reporting as unhealthy: {0}", unhealthy);
                } else {
                    LOGGER.log(Level.INFO, "{0} fewer health checks are reporting as unhealthy: {1}",
                            new Object[]{lastUnhealthy.size() - unhealthyName.size(), unhealthy});
                }
            } else if (lastUnhealthy != null) {
                LOGGER.log(Level.INFO, "All health checks are reporting as healthy");
            }
        }

        // TODO Remove once Jenkins 2.129+ see JENKINS-28983
        @Initializer(
                after = InitMilestone.EXTENSIONS_AUGMENTED
        )
        @Restricted(DoNotUse.class)
        public static void dynamicInstallHack() {
            if (Jenkins.getInstance().getInitLevel() == InitMilestone.COMPLETED) {
                // This is a dynamic plugin install
                VersionNumber version = Jenkins.getVersion();
                if (version != null && version.isOlderThan(new VersionNumber("2.129"))) {
                    PeriodicWork p = ExtensionList.lookup(PeriodicWork.class).get(HealthChecker.class);
                    if (p != null) {
                        jenkins.util.Timer.get().scheduleAtFixedRate(p, p.getInitialDelay(), p.getRecurrencePeriod(),
                                TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
    }

    /**
     * Health check data.
     */
    @ThreadSafe
    public static class HealthCheckData {
        /**
         * When the health check data was created.
         */
        private final long lastModified;
        /**
         * When the health check data is expected to be replaced with a newer result.
         */
        @CheckForNull
        private final Long expires;
        /**
         * The results.
         */
        @NonNull
        private final SortedMap<String, HealthCheck.Result> results;

        /**
         * Constructor for when you know how long before the next collection.
         *
         * @param results    the current results.
         * @param nextMillis how long until the next results will be available.
         */
        public HealthCheckData(@NonNull SortedMap<String, Result> results, long nextMillis) {
            this.results = results;
            this.lastModified = System.currentTimeMillis();
            this.expires = lastModified + nextMillis;
        }

        /**
         * Constructor for when you do not know how long before the next collection.
         *
         * @param results the current results.
         */
        public HealthCheckData(@NonNull SortedMap<String, Result> results) {
            this.results = results;
            this.lastModified = System.currentTimeMillis();
            this.expires = null;
        }

        /**
         * The number of milliseconds since 1st January 1970 GMT when the results were collected.
         *
         * @return The number of milliseconds since 1st January 1970 GMT when the results were collected.
         */
        public long getLastModified() {
            return lastModified;
        }

        /**
         * The number of milliseconds since 1st January 1970 GMT when the results are expected to be superceded by a
         * newer result.
         *
         * @return The number of milliseconds since 1st January 1970 GMT when the results are expected to be
         * superceded by a newer result or {@code null}
         */
        @CheckForNull
        public Long getExpires() {
            return expires;
        }

        /**
         * The results.
         *
         * @return the results.
         */
        @NonNull
        public SortedMap<String, Result> getResults() {
            return results;
        }
    }

}
