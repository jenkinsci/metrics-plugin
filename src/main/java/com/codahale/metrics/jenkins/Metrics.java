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

package com.codahale.metrics.jenkins;

import com.codahale.metrics.DerivativeGauge;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.jenkins.impl.MetricsFilter;
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
import hudson.util.DaemonThreadFactory;
import hudson.util.ExceptionCatchingThreadFactory;
import hudson.util.PluginServletFilter;
import hudson.util.StreamTaskListener;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

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
     * Thread pool for running health checks. We set the pool upper limit to 4 and we keep threads around for 5 seconds
     * as this is a bursty pool used once per minute.
     */
    private static final ExecutorService threadPoolForHealthChecks = new ThreadPoolExecutor(0, 4,
            5L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ExceptionCatchingThreadFactory(new DaemonThreadFactory(new ThreadFactory() {
                private final AtomicInteger number = new AtomicInteger();

                public Thread newThread(Runnable r) {
                    return new Thread(r, "Metrics-HealthChecks-" + number.incrementAndGet());
                }
            })));
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
     * {@inheritDoc}
     */
    @Override
    public void postInitialize() throws Exception {
        for (MetricProvider p : Jenkins.getInstance().getExtensionList(MetricProvider.class)) {
            metricRegistry.registerAll(p.getMetricSet());
        }
        for (HealthCheckProvider p : Jenkins.getInstance().getExtensionList(HealthCheckProvider.class)) {
            for (Map.Entry<String, HealthCheck> c : p.getHealthChecks().entrySet()) {
                healthCheckRegistry.register(c.getKey(), c.getValue());
            }
        }
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
     */
    @Extension
    public static class HeathCheckMetricsProvider extends MetricProvider {

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
                    metric(name("jenkins", "health-check", "inverse-score"), new DerivativeGauge<Double,Double>(c.getHealthCheckScore()) {
                        @Override
                        protected Double transform(Double value) {
                            return value == null ? null:1.0-value;
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

            listener.getLogger().println("Starting health checks at " + new Date());
            Timer.Context context = healthCheckDuration.time();
            SortedMap<String, HealthCheck.Result> results;
            try {
                results = registry.runHealthChecks(threadPoolForHealthChecks);
            } finally {
                context.stop();
            }
            listener.getLogger().println("Health check results at" + new Date() + ":");
            Set<String> unhealthy = null;
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
                    }
                    unhealthy.add(e.getKey());
                }
            }
            score = total / ((double) count);
            if (unhealthy != null) {
                LOGGER.log(Level.WARNING, "Some health checks are reporting as unhealthy: {0}", unhealthy);
            }
        }
    }

}
