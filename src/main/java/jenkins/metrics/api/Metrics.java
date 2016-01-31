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
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import jenkins.metrics.impl.MetricsFilter;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Plugin;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import hudson.util.PluginServletFilter;
import hudson.util.StreamTaskListener;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import jenkins.metrics.util.HealthChecksThreadPool;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
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
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

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
     * Returns the {@link HealthCheckRegistry} for the current {@link Jenkins}.
     *
     * @return the {@link HealthCheckRegistry} for the current {@link Jenkins}
     * @throws AssertionError if there is no {@link Jenkins} or {@link Metrics} or {@link HealthCheckRegistry}
     */
    @NonNull
    public static HealthCheckRegistry healthCheckRegistry() {
        Jenkins jenkins = Jenkins.getInstance();
        Metrics plugin = jenkins == null ? null : jenkins.getPlugin(Metrics.class);
        if (plugin == null || plugin.healthCheckRegistry == null) {
            throw new AssertionError(Metrics.class.getName() + " is missing its HealthCheckRegistry");
        }
        return plugin.healthCheckRegistry;
    }

    /**
     * Get the last health check results
     * 
     * @return a map with health check name -> health check result
     */
    @Nonnull
    public static SortedMap<String, Result> getHealthCheckResults() {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            LOGGER.warning("Unable to get health check results, client master is not ready (startup or shutdown)");
            return Collections.emptySortedMap();
        }
        HealthChecker healthChecker = jenkins.getExtensionList(PeriodicWork.class).get(HealthChecker.class);
        if (healthChecker == null) {
            LOGGER.warning("Unable to get health check results, HealthChecker is not available");
            return Collections.emptySortedMap();
        }
        return healthChecker.getSortedHealthCheckResults();
    }

    /**
     * Returns the {@link MetricRegistry} for the current {@link Jenkins}.
     *
     * @return the {@link MetricRegistry} for the current {@link Jenkins}
     * @throws AssertionError if there is no {@link Jenkins} or {@link Metrics} or {@link MetricRegistry}
     */
    @NonNull
    public static MetricRegistry metricRegistry() {
        Jenkins jenkins = Jenkins.getInstance();
        Metrics plugin = jenkins == null ? null : jenkins.getPlugin(Metrics.class);
        if (plugin == null || plugin.metricRegistry == null) {
            throw new AssertionError(Metrics.class.getName() + " is missing its MetricRegistry");
        }
        return plugin.metricRegistry;
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKey(@CheckForNull String accessKey) {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        descriptor.checkAccessKey(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyPing(@CheckForNull String accessKey) {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        descriptor.checkAccessKeyPing(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyThreadDump(@CheckForNull String accessKey) {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        descriptor.checkAccessKeyThreadDump(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyHealthCheck(@CheckForNull String accessKey) {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        descriptor.checkAccessKeyHealthCheck(accessKey);
    }

    /**
     * Checks an access key.
     *
     * @param accessKey the access key.
     */
    public static void checkAccessKeyMetrics(@CheckForNull String accessKey) {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        descriptor.checkAccessKeyMetrics(accessKey);
    }

    public static HttpResponse cors(@CheckForNull String accessKey, final HttpResponse resp) {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        return descriptor.cors(accessKey, resp);
    }


    /**
     * Re-indexes all the access keys from the different {@link MetricsAccessKey.Provider} extensions.
     */
    public static void reindexAccessKeys() {
        Jenkins jenkins = Jenkins.getInstance();
        MetricsAccessKey.DescriptorImpl descriptor = jenkins == null
                ? null
                : jenkins.getDescriptorByType(MetricsAccessKey.DescriptorImpl.class);
        if (descriptor == null) {
            throw new IllegalStateException();
        }
        descriptor.reindexAccessKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws Exception {
        PluginServletFilter.addFilter(filter);
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
        Metrics plugin = jenkins == null ? null : jenkins.getPlugin(Metrics.class);
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
        for (MetricProvider p : Jenkins.getInstance().getExtensionList(MetricProvider.class)) {
            LOGGER.log(Level.FINER, "Registering metric provider {0} (type {1})", new Object[]{p, p.getClass()});
            plugin.metricRegistry.registerAll(p.getMetricSet());
        }
        for (HealthCheckProvider p : Jenkins.getInstance().getExtensionList(HealthCheckProvider.class)) {
            LOGGER.log(Level.FINER, "Registering health check provider {0} (type {1})", new Object[]{p, p.getClass()});
            Map<String, HealthCheck> healthChecks = p.getHealthChecks();
            for (Map.Entry<String, HealthCheck> c : healthChecks.entrySet()) {
                plugin.healthCheckRegistry.register(c.getKey(), c.getValue());
            }
            LOGGER.log(Level.FINER, "Registered health check provider {0} (type {1}) with {2} checks: {3}",
                    new Object[] { p, p.getClass(), healthChecks.size(), healthChecks.keySet() });
        }
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
            HealthChecker c =
                    Jenkins.getInstance().getExtensionList(PeriodicWork.class).get(HealthChecker.class);
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
    @Extension
    public static class HealthChecker extends PeriodicWork {

        private final Timer healthCheckDuration = new Timer();

        private SortedMap<String, HealthCheck.Result> healthCheckResults = new TreeMap<String, HealthCheck.Result>();

        private final Gauge<Integer> healthCheckCount = new Gauge<Integer>() {
            public Integer getValue() {
                return healthCheckRegistry().getNames().size();
            }
        };
        private final Gauge<Double> healthCheckScore = new Gauge<Double>() {
            public Double getValue() {
                return score;
            }
        };
        private Future<?> future;
        private volatile double score = 1.0;
        private volatile Set<String> lastUnhealthy = null;

        public HealthChecker() {
            super();
        }

        public long getRecurrencePeriod() {
            return TimeUnit2.MINUTES.toMillis(Math.min(Math.max(1, HEALTH_CHECK_INTERVAL_MINS),
                    TimeUnit2.DAYS.toMinutes(1)));
        }

        public Timer getHealthCheckDuration() {
            return healthCheckDuration;
        }

        public Map<String, HealthCheck.Result> getHealthCheckResults() {
            return getSortedHealthCheckResults();
        }

        public SortedMap<String, HealthCheck.Result> getSortedHealthCheckResults() {
            return healthCheckResults;
        }

        public Gauge<Integer> getHealthCheckCount() {
            return healthCheckCount;
        }

        public Gauge<Double> getHealthCheckScore() {
            return healthCheckScore;
        }

        /**
         * Schedules this periodic work now in a new thread, if one isn't already running.
         */
        public final void doRun() {
            try {
                if (future != null && !future.isDone()) {
                    logger.log(Level.INFO,
                            HealthChecker.class.getName() + " thread is still running. Execution aborted.");
                    return;
                }
                if (threadPoolForHealthChecks == null) {
                    LOGGER.info("Health checks thread pool not yet initialized, skipping until next execution");
                    return;
                }
                future = threadPoolForHealthChecks.submit(new Runnable() {
                    public void run() {
                        logger.log(Level.FINE, "Started " + HealthChecker.class.getName());
                        long startTime = System.currentTimeMillis();

                        StreamTaskListener l = null;
                        try {
                            l = new StreamTaskListener(new File(Jenkins.getInstance().getRootDir(),
                                    HealthChecker.class.getName() + ".log"));
                            ACL.impersonate(ACL.SYSTEM);

                            execute(l);
                        } catch (IOException e) {
                            if (l != null) {
                                e.printStackTrace(l.fatalError(e.getMessage()));
                            } else {
                                logger.log(Level.SEVERE,
                                        HealthChecker.class.getName() + " could not create listener", e);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace(l.fatalError("aborted"));
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Error running " + HealthChecker.class.getName(), e);
                            if (l != null) {
                                e.printStackTrace(l.fatalError(e.getMessage()));
                            }
                        } finally {
                            if (l != null) {
                                l.closeQuietly();
                            }
                        }

                        logger.log(Level.FINE, "Finished " + HealthChecker.class.getName() + ". " +
                                (System.currentTimeMillis() - startTime) + " ms");
                    }
                });
            } catch (Throwable t) {
                logger.log(Level.SEVERE, HealthChecker.class.getName() + " thread failed with error", t);
            }
        }

        private void execute(TaskListener listener) throws IOException, InterruptedException {
            reindexAccessKeys();
            HealthCheckRegistry registry = healthCheckRegistry();
            // update the active health checks
            Set<String> defined = registry.getNames();
            Set<String> removed = new HashSet<String>(defined);
            for (HealthCheckProvider p : Jenkins.getInstance().getExtensionList(HealthCheckProvider.class)) {
                for (Map.Entry<String, HealthCheck> c : p.getHealthChecks().entrySet()) {
                    removed.remove(c.getKey());
                    if (!defined.contains(c.getKey())) {
                        registry.register(c.getKey(), c.getValue());
                        defined.add(c.getKey());
                    }
                }
            }
            for (String key: removed) {
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
            listener.getLogger().println("Health check results at " + new Date() + ":");
            Set<String> unhealthy = null;
            Set<String> unhealthyName = null;
            int count = 0;
            int total = 0;
            for (Map.Entry<String, HealthCheck.Result> e : results.entrySet()) {
                count++;
                listener.getLogger().println(" * " + e.getKey() + ": " + e.getValue());
                healthCheckResults.put(e.getKey(), e.getValue());
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
            healthCheckResults.keySet().retainAll(results.keySet());

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
    }

}
