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

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.jenkins.impl.MetricsFilter;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Plugin;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import hudson.util.PluginServletFilter;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Stephen Connolly
 */
public class Metrics extends Plugin {

    public static final int HEALTH_CHECK_INTERVAL_MINS =
            Integer.getInteger(Metrics.class.getName() + ".HEALTH_CHECK_INTERVAL_MINS", 1);
    private static final Logger LOGGER = Logger.getLogger(Metrics.class.getName());
    public static final PermissionGroup PERMISSIONS =
            new PermissionGroup(Metrics.class, Messages._Metrics_PermissionGroup());
    public static final Permission VIEW = new Permission(PERMISSIONS,
            "View", Messages._Metrics_ViewPermission_Description(), Jenkins.ADMINISTER, PermissionScope.JENKINS);
    private transient MetricRegistry metricRegistry;
    private transient HealthCheckRegistry healthCheckRegistry;
    private transient MetricsFilter filter;

    @CheckForNull
    public static HealthCheckRegistry healthCheckRegistry() {
        Jenkins jenkins = Jenkins.getInstance();
        Metrics plugin = jenkins == null ? null : jenkins.getPlugin(Metrics.class);
        return plugin == null ? null : plugin.healthCheckRegistry;
    }

    @CheckForNull
    public static MetricRegistry metricRegistry() {
        Jenkins jenkins = Jenkins.getInstance();
        Metrics plugin = jenkins == null ? null : jenkins.getPlugin(Metrics.class);
        return plugin == null ? null : plugin.metricRegistry;
    }

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

    @Override
    public void start() throws Exception {
        metricRegistry = new MetricRegistry();
        healthCheckRegistry = new HealthCheckRegistry();
        filter = new MetricsFilter();
        PluginServletFilter.addFilter(filter);
    }

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

    @Override
    public void stop() throws Exception {
        if (filter != null) {
            PluginServletFilter.removeFilter(filter);
            filter = null;
        }
        metricRegistry = null;
        healthCheckRegistry = null;
    }

    @Extension
    public static class PeriodicWorkImpl extends AsyncPeriodicWork {

        public PeriodicWorkImpl() {
            super(HealthCheckRegistry.class.getName());
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit2.MINUTES.toMillis(Math.min(Math.max(1, HEALTH_CHECK_INTERVAL_MINS),
                    TimeUnit2.DAYS.toMinutes(1)));
        }

        @Override
        protected void execute(TaskListener listener) throws IOException, InterruptedException {
            reindexAccessKeys();
            HealthCheckRegistry registry = healthCheckRegistry();
            if (registry != null) {
                listener.getLogger().println("Starting health checks at " + new Date());
                SortedMap<String, HealthCheck.Result> results = registry.runHealthChecks();
                listener.getLogger().println("Health check results:");
                Set<String> unhealthy = null;
                for (Map.Entry<String, HealthCheck.Result> e : results.entrySet()) {
                    listener.getLogger().println(" * " + e.getKey() + ": " + e.getValue());
                    if (!e.getValue().isHealthy()) {
                        if (unhealthy == null) {
                            unhealthy = new TreeSet<String>();
                        }
                        unhealthy.add(e.getKey());
                    }
                }
                if (unhealthy != null) {
                    LOGGER.log(Level.WARNING, "Some health checks are reporting as unhealthy: {0}", unhealthy);
                }
            }
        }
    }

}
