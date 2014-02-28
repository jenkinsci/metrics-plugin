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

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.DerivativeGauge;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jenkins.AutoSamplingHistogram;
import com.codahale.metrics.jenkins.MetricProvider;
import com.codahale.metrics.jenkins.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import hudson.model.queue.WorkUnit;
import hudson.model.queue.WorkUnitContext;
import jenkins.model.Jenkins;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * @author Stephen Connolly
 */
@Extension
public class JenkinsMetricProviderImpl extends MetricProvider {

    private AutoSamplingHistogram jenkinsNodeTotalCount;
    private AutoSamplingHistogram jenkinsNodeOnlineCount;
    private AutoSamplingHistogram jenkinsExecutorTotalCount;
    private AutoSamplingHistogram jenkinsExecutorUsedCount;
    private Timer jenkinsBuildDuration;
    private Meter jenkinsJobScheduleRate;
    private Map<Computer, Timer> computerBuildDurations = new HashMap<Computer, Timer>();
    private MetricSet set;
    private Timer jenkinsJobScheduleTime;

    public JenkinsMetricProviderImpl() {
        Gauge<QueueStats> jenkinsQueue = new CachedGauge<QueueStats>(1, TimeUnit.SECONDS) {
            @Override
            protected QueueStats loadValue() {
                Queue queue = Jenkins.getInstance().getQueue();
                int length = 0;
                int blocked = 0;
                int buildable = 0;
                int pending = queue.getPendingItems().size();
                int stuck = 0;
                for (Queue.Item i : queue.getItems()) {
                    length++;
                    if (i.isBlocked()) {
                        blocked++;
                    }
                    if (i.isBuildable()) {
                        buildable++;
                    }
                    if (i.isStuck()) {
                        stuck++;
                    }
                }
                return new QueueStats(length, blocked, buildable, pending, stuck);
            }
        };
        Gauge<NodeStats> jenkinsNodes = new

                CachedGauge<NodeStats>(1, TimeUnit.SECONDS) {
                    @Override
                    protected NodeStats loadValue() {
                        int nodeCount = 0;
                        int nodeOnline = 0;
                        int executorCount = 0;
                        int executorBuilding = 0;
                        Jenkins jenkins = Jenkins.getInstance();
                        if (jenkins.getNumExecutors() > 0) {
                            nodeCount++;
                            Computer computer = jenkins.toComputer();
                            if (computer != null) {
                                if (!computer.isOffline()) {
                                    nodeOnline++;
                                    for (Executor e : computer.getExecutors()) {
                                        executorCount++;
                                        if (!e.isIdle()) {
                                            executorBuilding++;
                                        }
                                    }
                                }
                            }
                        }
                        for (Node node : jenkins.getNodes()) {
                            nodeCount++;
                            Computer computer = node.toComputer();
                            if (computer == null) {
                                continue;
                            }
                            if (!computer.isOffline()) {
                                nodeOnline++;
                                for (Executor e : computer.getExecutors()) {
                                    executorCount++;
                                    if (!e.isIdle()) {
                                        executorBuilding++;
                                    }
                                }
                            }
                        }
                        return new NodeStats(nodeCount, nodeOnline, executorCount, executorBuilding);
                    }
                };

        set = metrics(metric(name("jenkins", "queue", "size"),
                new AutoSamplingHistogram(new DerivativeGauge<QueueStats, Integer>(jenkinsQueue) {
                    @Override
                    protected Integer transform(QueueStats value) {
                        return value.getLength();
                    }
                }).toMetricSet()),
                metric(name("jenkins", "queue", "blocked"),
                        new AutoSamplingHistogram(new DerivativeGauge<QueueStats, Integer>(jenkinsQueue) {
                            @Override
                            protected Integer transform(QueueStats value) {
                                return value.getBlocked();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "queue", "buildable"),
                        new AutoSamplingHistogram(new DerivativeGauge<QueueStats, Integer>(jenkinsQueue) {
                            @Override
                            protected Integer transform(QueueStats value) {
                                return value.getBuildable();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "queue", "stuck"),
                        new AutoSamplingHistogram(new DerivativeGauge<QueueStats, Integer>(jenkinsQueue) {
                            @Override
                            protected Integer transform(QueueStats value) {
                                return value.getStuck();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "queue", "pending"),
                        new AutoSamplingHistogram(new DerivativeGauge<QueueStats, Integer>(jenkinsQueue) {
                            @Override
                            protected Integer transform(QueueStats value) {
                                return value.getPending();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "job", "scheduled"), (jenkinsJobScheduleRate = new Meter())),
                metric(name("jenkins", "job", "queue", "duration"), (jenkinsJobScheduleTime = new Timer())),
                metric(name("jenkins", "node", "count"), (jenkinsNodeTotalCount =
                        new AutoSamplingHistogram(new DerivativeGauge<NodeStats, Integer>(jenkinsNodes) {
                            @Override
                            protected Integer transform(NodeStats value) {
                                return value.getNodeCount();
                            }
                        })).toMetricSet()),
                metric(name("jenkins", "node", "online"), (jenkinsNodeOnlineCount =
                        new AutoSamplingHistogram(new DerivativeGauge<NodeStats, Integer>(jenkinsNodes) {
                            @Override
                            protected Integer transform(NodeStats value) {
                                return value.getNodeOnline();
                            }
                        })).toMetricSet()),
                metric(name("jenkins", "executor", "count"), (jenkinsExecutorTotalCount =
                        new AutoSamplingHistogram(new DerivativeGauge<NodeStats, Integer>(jenkinsNodes) {
                            @Override
                            protected Integer transform(NodeStats value) {
                                return value.getExecutorCount();
                            }
                        })).toMetricSet()),
                metric(name("jenkins", "executor", "in-use"), (jenkinsExecutorUsedCount =
                        new AutoSamplingHistogram(new DerivativeGauge<NodeStats, Integer>(jenkinsNodes) {
                            @Override
                            protected Integer transform(NodeStats value) {
                                return value.getExecutorBuilding();
                            }
                        })).toMetricSet()),
                metric(name("jenkins", "job", "build", "duration"), (jenkinsBuildDuration = new Timer()))
        );
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
        if (jenkins != null) {
            Set<Computer> forRetention = new HashSet<Computer>();
            for (Node node : jenkins.getNodes()) {
                Computer computer = node.toComputer();
                if (computer == null) {
                    continue;
                }
                forRetention.add(computer);
                getOrCreateTimer(computer);
            }
            MetricRegistry metricRegistry = Metrics.metricRegistry();
            if (metricRegistry != null) {
                for (Map.Entry<Computer, Timer> entry : computerBuildDurations.entrySet()) {
                    if (forRetention.contains(entry.getKey())) {
                        continue;
                    }
                    // purge dead nodes
                    metricRegistry.remove(name("jenkins", "node", entry.getKey().getName(), "builds"));
                }
            }
            computerBuildDurations.keySet().retainAll(forRetention);
        }

    }

    private synchronized Timer getOrCreateTimer(Computer computer) {
        Timer timer = computerBuildDurations.get(computer);
        if (timer == null) {
            MetricRegistry registry = Metrics.metricRegistry();
            timer = registry == null
                    ? new Timer()
                    : registry.timer(name("jenkins", "node", computer.getName(), "builds"));
            computerBuildDurations.put(computer, timer);
        }
        return timer;
    }

    private static class QueueStats {
        private final int length;
        private final int blocked;
        private final int buildable;
        private final int stuck;
        private final int pending;

        public QueueStats(int length, int blocked, int buildable, int pending, int stuck) {
            this.length = length;
            this.blocked = blocked;
            this.buildable = buildable;
            this.pending = pending;
            this.stuck = stuck;
        }

        public int getBlocked() {
            return blocked;
        }

        public int getBuildable() {
            return buildable;
        }

        public int getLength() {
            return length;
        }

        public int getPending() {
            return pending;
        }

        public int getStuck() {
            return stuck;
        }
    }

    private static class NodeStats {
        private final int nodeCount;
        private final int nodeOnline;
        private final int executorCount;
        private final int executorBuilding;

        public NodeStats(int nodeCount, int nodeOnline, int executorCount, int executorBuilding) {
            this.nodeCount = nodeCount;
            this.nodeOnline = nodeOnline;
            this.executorCount = executorCount;
            this.executorBuilding = executorBuilding;
        }

        public int getExecutorAvailable() {
            return executorCount - executorBuilding;
        }

        public int getExecutorBuilding() {
            return executorBuilding;
        }

        public int getExecutorCount() {
            return executorCount;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public int getNodeOffline() {
            return nodeCount - nodeOnline;
        }

        public int getNodeOnline() {
            return nodeOnline;
        }
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {

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
            ScheduledRate.instance().addAction(run);
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

    @Extension(ordinal = Double.MAX_VALUE)
    public static class SchedulingRate extends Queue.QueueDecisionHandler {

        @Override
        public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null && instance.jenkinsJobScheduleRate != null) {
                instance.jenkinsJobScheduleRate.mark();
            }
            return true;
        }
    }

    @Extension
    public static class ScheduledRate extends QueueListener {

        private final Map<WorkUnitContext, JobScheduledDuration> actions = new WeakHashMap();

        public static ScheduledRate instance() {
            return Jenkins.getInstance().getExtensionList(QueueListener.class).get(ScheduledRate.class);
        }

        public void addAction(Run run) {
            Executor executor = run.getExecutor();
            if (executor == null) {
                return;
            }
            WorkUnit workUnit = executor.getCurrentWorkUnit();
            if (workUnit == null) {
                return;
            }
            WorkUnitContext context = workUnit.context;
            if (context == null) {
                return;
            }
            ;
            JobScheduledDuration action;
            synchronized (actions) {
                action = actions.remove(context);
            }
            if (action != null) {
                run.addAction(action);
            }
        }

        public void onLeft(Queue.LeftItem li) {
            long millisecondsInQueue = System.currentTimeMillis() - li.getInQueueSince();
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsJobScheduleTime != null) {
                instance.jenkinsJobScheduleTime.update(millisecondsInQueue, TimeUnit.MILLISECONDS);
            }
            if (li.outcome != null) {
                synchronized (actions) {
                    actions.put(li.outcome, new JobScheduledDuration(millisecondsInQueue));
                }
            }
        }

    }

    public static class JobScheduledDuration implements Serializable, Action {

        private static final long serialVersionUID = 1L;
        private final long scheduledTimeMillis;

        public JobScheduledDuration(long scheduledTimeMillis) {
            this.scheduledTimeMillis = scheduledTimeMillis;
        }

        public long getScheduledTimeMillis() {
            return scheduledTimeMillis;
        }

        public String getIconFileName() {
            return null;
        }

        public String getDisplayName() {
            return null;
        }

        public String getUrlName() {
            return null;
        }
    }

    public static class ScheduledTime implements Serializable, Action {

        private static final long serialVersionUID = 1L;
        private final long scheduledTimeMillis;

        public ScheduledTime(long scheduledTimeMillis) {
            this.scheduledTimeMillis = scheduledTimeMillis;
        }

        public long getScheduledTimeMillis() {
            return scheduledTimeMillis;
        }

        public String getIconFileName() {
            return null;
        }

        public String getDisplayName() {
            return null;
        }

        public String getUrlName() {
            return null;
        }
    }

}
