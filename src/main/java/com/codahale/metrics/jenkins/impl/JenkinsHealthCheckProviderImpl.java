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

package com.codahale.metrics.jenkins.impl;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.jvm.ThreadDeadlockHealthCheck;
import com.codahale.metrics.jenkins.HealthCheckProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.PluginManager;
import hudson.model.Computer;
import hudson.model.ComputerSet;
import hudson.node_monitors.DiskSpaceMonitor;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.node_monitors.TemporarySpaceMonitor;
import jenkins.model.Jenkins;

import java.util.List;
import java.util.Map;

/**
 * Provides some simple standard health checks.
 */
@Extension
public class JenkinsHealthCheckProviderImpl extends HealthCheckProvider {
    @NonNull
    @Override
    public Map<String, HealthCheck> getHealthChecks() {
        return checks(
                check("plugins", new HealthCheck() {
                    @Override
                    protected Result check() throws Exception {
                        List<PluginManager.FailedPlugin> failedPlugins =
                                Jenkins.getInstance().getPluginManager().getFailedPlugins();
                        if (failedPlugins.isEmpty()) {
                            return Result.healthy("No failed plugins");
                        }
                        return Result.unhealthy("There are {0} failed plugins", failedPlugins.size());
                    }
                }),
                check("thread-deadlock", new ThreadDeadlockHealthCheck()),
                check("disk-space", new HealthCheck() {
                    @Override
                    protected Result check() throws Exception {
                        DiskSpaceMonitor m = ComputerSet.getMonitors().get(DiskSpaceMonitor.class);
                        if (m == null) {
                            return Result.healthy();
                        }
                        for (Computer c : Jenkins.getInstance().getComputers()) {
                            DiskSpaceMonitorDescriptor.DiskSpace freeSpace = m.getFreeSpace(c);
                            if (m.getThresholdBytes() > freeSpace.size) {
                                return Result.unhealthy("Only {0} Gb free on {1}", freeSpace.getGbLeft(), c.getName());
                            }
                        }
                        return Result.healthy();
                    }
                }, ComputerSet.getMonitors().get(DiskSpaceMonitor.class) != null),
                check("temporary-space", new HealthCheck() {
                    @Override
                    protected Result check() throws Exception {
                        TemporarySpaceMonitor m = ComputerSet.getMonitors().get(TemporarySpaceMonitor.class);
                        if (m == null) {
                            return Result.healthy();
                        }
                        for (Computer c : Jenkins.getInstance().getComputers()) {
                            DiskSpaceMonitorDescriptor.DiskSpace freeSpace = m.getFreeSpace(c);
                            if (m.getThresholdBytes() > freeSpace.size) {
                                return Result.unhealthy("Only {0} Gb free on {1}", freeSpace.getGbLeft(), c.getName());
                            }
                        }
                        return Result.healthy();
                    }
                }, ComputerSet.getMonitors().get(DiskSpaceMonitor.class) != null)
        );
    }
}
