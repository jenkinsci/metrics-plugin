/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jenkins.MetricProvider;
import com.codahale.metrics.jenkins.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * @author Stephen Connolly
 */
@Extension
public class JenkinsMetricProviderImpl extends MetricProvider {
    private Histogram jenkinsQueueLength;
    private Histogram jenkinsNodeTotalCount;
    private Histogram jenkinsNodeOnlineCount;
    private Histogram jenkinsExecutorTotalCount;
    private Histogram jenkinsExecutorUsedCount;
    private Timer jenkinsBuildDuration;
    private Map<Computer, Timer> computerBuildDurations = new HashMap<Computer, Timer>();
    private Map<String, Metric> metrics;
    private MetricSet set;

    public JenkinsMetricProviderImpl() {
        metrics = new LinkedHashMap<String, Metric>();
        jenkinsQueueLength = new Histogram(new ExponentiallyDecayingReservoir());
        metrics.put(name("jenkins", "queue", "size"), jenkinsQueueLength);
        jenkinsNodeTotalCount = new Histogram(new ExponentiallyDecayingReservoir());
        metrics.put(name("jenkins", "node", "count"), jenkinsNodeTotalCount);
        jenkinsNodeOnlineCount = new Histogram(new ExponentiallyDecayingReservoir());
        metrics.put(name("jenkins", "node", "online"), jenkinsNodeOnlineCount);
        jenkinsExecutorTotalCount = new Histogram(new ExponentiallyDecayingReservoir());
        metrics.put(name("jenkins", "executor", "count"), jenkinsExecutorTotalCount);
        jenkinsExecutorUsedCount = new Histogram(new ExponentiallyDecayingReservoir());
        metrics.put(name("jenkins", "executor", "in-use"), jenkinsExecutorUsedCount);
        jenkinsBuildDuration = new Timer();
        metrics.put(name("jenkins", "build", "duration"), jenkinsBuildDuration);
        set = new MetricSet() {
            public Map<String, Metric> getMetrics() {
                return metrics;
            }
        };
    }

    public static JenkinsMetricProviderImpl instance() {
        return Jenkins.getInstance().getExtensionList(MetricProvider.class).get(JenkinsMetricProviderImpl.class);
    }

    @NonNull
    @Override
    public MetricSet getMetricSet() {
        return set;
    }

    public Histogram getJenkinsExecutorTotalCount() {
        return jenkinsExecutorTotalCount;
    }

    public Histogram getJenkinsExecutorUsedCount() {
        return jenkinsExecutorUsedCount;
    }

    public Histogram getJenkinsNodeOnlineCount() {
        return jenkinsNodeOnlineCount;
    }

    public Histogram getJenkinsNodeTotalCount() {
        return jenkinsNodeTotalCount;
    }

    private synchronized void updateMetrics() {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkinsQueueLength != null) {
            jenkinsQueueLength.update(jenkins.getQueue().getBuildableItems().size());
        }
        if (jenkinsNodeTotalCount != null || jenkinsNodeOnlineCount != null || jenkinsExecutorTotalCount != null
                || jenkinsExecutorUsedCount != null) {
            int nodeTotal = 0;
            int nodeOnline = 0;
            int executorTotal = 0;
            int executorUsed = 0;
            if (jenkins.getNumExecutors() > 0) {
                nodeTotal++;
                Computer computer = jenkins.toComputer();
                if (computer != null) {
                    if (!computer.isOffline()) {
                        nodeOnline++;
                        for (Executor e : computer.getExecutors()) {
                            executorTotal++;
                            if (!e.isIdle()) {
                                executorUsed++;
                            }
                        }
                    }
                }
            }
            Set<Computer> forRetention = new HashSet<Computer>();
            for (Node node : jenkins.getNodes()) {
                nodeTotal++;
                Computer computer = node.toComputer();
                if (computer == null) {
                    continue;
                }
                if (!computer.isOffline()) {
                    nodeOnline++;
                    for (Executor e : computer.getExecutors()) {
                        executorTotal++;
                        if (!e.isIdle()) {
                            executorUsed++;
                        }
                    }
                }
                forRetention.add(computer);
                getOrCreateTimer(computer);
            }
            for (Map.Entry<Computer, Timer> entry : computerBuildDurations.entrySet()) {
                if (forRetention.contains(entry.getKey())) {
                    continue;
                }
                // purge dead nodes
                Metrics.metricRegistry().remove(name(Node.class, "builds", entry.getKey().getName()));
            }
            computerBuildDurations.keySet().retainAll(forRetention);
            if (jenkinsNodeTotalCount != null) {
                jenkinsNodeTotalCount.update(nodeTotal);
            }
            if (jenkinsNodeOnlineCount != null) {
                jenkinsNodeOnlineCount.update(nodeOnline);
            }
            if (jenkinsExecutorTotalCount != null) {
                jenkinsExecutorTotalCount.update(executorTotal);
            }
            if (jenkinsExecutorUsedCount != null) {
                jenkinsExecutorUsedCount.update(executorUsed);
            }
        }

    }

    private synchronized Timer getOrCreateTimer(Computer computer) {
        Timer timer = computerBuildDurations.get(computer);
        if (timer == null) {
            timer = Metrics.metricRegistry().timer(name(Node.class, "builds", computer.getName()));
            computerBuildDurations.put(computer, timer);
        }
        return timer;
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {

        private Thread thread;

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(15);
        }

        @Override
        protected synchronized void doRun() throws Exception {
            final JenkinsMetricProviderImpl instance = instance();
            if (instance == null) {
                return;
            }
            instance.updateMetrics();
        }

    }

    @Extension
    public static class RunListenerImpl extends RunListener<Run> {
        private Map<Run, List<Timer.Context>> contexts = new HashMap<Run, List<Timer.Context>>();

        @Override
        public synchronized void onStarted(Run run, TaskListener listener) {
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null) {
                List<Timer.Context> contextList = new ArrayList<Timer.Context>();
                contextList.add(instance.jenkinsBuildDuration.time());
                Executor executor = run.getExecutor();
                if (executor != null) {
                    Computer computer = executor.getOwner();
                    Timer timer = instance.getOrCreateTimer(computer);
                    contextList.add(timer.time());
                }
                contexts.put(run, contextList);
            }
        }

        @Override
        public synchronized void onCompleted(Run run, TaskListener listener) {
            List<Timer.Context> contextList = contexts.remove(run);
            if (contextList != null) {
                for (Timer.Context context : contextList) {
                    context.stop();
                }
            }
        }
    }


}
