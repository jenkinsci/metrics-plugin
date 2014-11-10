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

package jenkins.metrics.impl;

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.Counter;
import com.codahale.metrics.DerivativeGauge;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Project;
import jenkins.metrics.util.AutoSamplingHistogram;
import jenkins.metrics.api.MetricProvider;
import jenkins.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import hudson.model.queue.WorkUnit;
import hudson.model.queue.WorkUnitContext;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * @author Stephen Connolly
 */
@Extension
public class JenkinsMetricProviderImpl extends MetricProvider {

    /**
     * Our set of metrics.
     */
    private MetricSet set;
    /**
     * The number of nodes defined in this Jenkins instance.
     */
    private AutoSamplingHistogram jenkinsNodeTotalCount;
    /**
     * The number of defined nodes that are on-line.
     */
    private AutoSamplingHistogram jenkinsNodeOnlineCount;
    /**
     * The number of executors that are on-line.
     */
    private AutoSamplingHistogram jenkinsExecutorTotalCount;
    /**
     * The number of executors that are in-use.
     */
    private AutoSamplingHistogram jenkinsExecutorUsedCount;
    /**
     * The build durations per computer.
     */
    private Map<Computer, Timer> computerBuildDurations = new HashMap<Computer, Timer>();
    /**
     * The rate at which jobs are being scheduled.
     */
    private Meter jenkinsJobScheduleRate;
    /**
     * The amount of time jobs stay in the queue.
     */
    private Timer jenkinsJobQueueTime;
    /**
     * The amount of time a job is waiting for its quiet period to expire.
     */
    private Timer jenkinsJobWaitingTime;
    /**
     * The amount of time jobs are blocked waiting for a resource that has a restricted sharing policy.
     */
    private Timer jenkinsJobBlockedTime;
    /**
     * The amount of time jobs are buildable and waiting for an executor.
     */
    private Timer jenkinsJobBuildableTime;
    /**
     * The amount of time jobs are building.
     */
    private Timer jenkinsJobBuildingTime;
    /**
     * Run Results.
     */
    private HashMap<String, Meter> jenkinsRunResults = new HashMap<String, Meter>();
    /**
     * The amount of time jobs take from initial scheduling to completion.
     */
    private Timer jenkinsJobTotalTime;

    public JenkinsMetricProviderImpl() {
        Gauge<QueueStats> jenkinsQueue = new CachedGauge<QueueStats>(1, TimeUnit.SECONDS) {
            @Override
            protected QueueStats loadValue() {
                SecurityContext oldContext = ACL.impersonate(ACL.SYSTEM);
                try {
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
                } finally {
                    SecurityContextHolder.setContext(oldContext);
                }
            }
        };
        Gauge<NodeStats> jenkinsNodes = new

                CachedGauge<NodeStats>(1, TimeUnit.SECONDS) {
                    @Override
                    protected NodeStats loadValue() {
                        SecurityContext oldContext = ACL.impersonate(ACL.SYSTEM);
                        try {
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
                        } finally {
                            SecurityContextHolder.setContext(oldContext);
                        }
                    }
                };
        Gauge<JobStats> jobStats = new CachedGauge<JobStats>(5, TimeUnit.MINUTES) {
            @Override
            protected JobStats loadValue() {
                SecurityContext oldContext = ACL.impersonate(ACL.SYSTEM);
                try {
                    int count = 0;
                    int disabledProjects = 0;
                    int projectCount = 0;
                    Stack<ItemGroup> q = new Stack<ItemGroup>();
                    q.push(Jenkins.getInstance());
                    while (!q.isEmpty()) {
                        ItemGroup<?> parent = q.pop();
                        for (Item i : parent.getItems()) {
                            if (i instanceof Job) {
                                count++;
                                if (i instanceof Project) {
                                    projectCount ++;
                                    if (((Project) i).isDisabled()) {
                                        disabledProjects++;
                                    }
                                }
                            }
                            if (i instanceof ItemGroup) {
                                q.push((ItemGroup) i);
                            }
                        }
                    }
                    return new JobStats(count, projectCount,disabledProjects);
                } finally {
                    SecurityContextHolder.setContext(oldContext);
                }
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
                metric(name("jenkins", "node", "offline"),
                        new AutoSamplingHistogram(new DerivativeGauge<NodeStats, Integer>(jenkinsNodes) {
                            @Override
                            protected Integer transform(NodeStats value) {
                                return value.getNodeOffline();
                            }
                        }).toMetricSet()),
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
                metric(name("jenkins", "executor", "free"),
                        new AutoSamplingHistogram(new DerivativeGauge<NodeStats, Integer>(jenkinsNodes) {
                            @Override
                            protected Integer transform(NodeStats value) {
                                return value.getExecutorAvailable();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "job", "scheduled"), (jenkinsJobScheduleRate = new Meter())),
                metric(name("jenkins", "job", "count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats,Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getJobCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "project", "count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats,Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getProjectCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "project", "enabled","count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats,Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getEnabledProjectCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "project", "disabled","count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats,Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getDisabledProjectCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "job", "queuing", "duration"), (jenkinsJobQueueTime = new Timer())),
                metric(name("jenkins", "job", "waiting", "duration"), (jenkinsJobWaitingTime = new Timer())),
                metric(name("jenkins", "job", "blocked", "duration"), (jenkinsJobBlockedTime = new Timer())),
                metric(name("jenkins", "job", "buildable", "duration"), (jenkinsJobBuildableTime = new Timer())),
                metric(name("jenkins", "job", "building", "duration"), (jenkinsJobBuildingTime = new Timer())),
                metric(name("jenkins", "job", "total", "duration"), (jenkinsJobTotalTime = new Timer())),
                metric(name("jenkins", "plugins", "active"), new CachedGauge<Integer>(5, TimeUnit.MINUTES) {
                    @Override
                    protected Integer loadValue() {
                        int count = 0;
                        for (PluginWrapper w : Jenkins.getInstance().getPluginManager().getPlugins()) {
                            if (w.isActive()) {
                                count++;
                            }
                        }
                        return count;
                    }
                }),
                metric(name("jenkins", "plugins", "inactive"), new CachedGauge<Integer>(5, TimeUnit.MINUTES) {
                    @Override
                    protected Integer loadValue() {
                        int count = 0;
                        for (PluginWrapper w : Jenkins.getInstance().getPluginManager().getPlugins()) {
                            if (!w.isActive()) {
                                count++;
                            }
                        }
                        return count;
                    }
                }),
                metric(name("jenkins", "plugins", "failed"), new CachedGauge<Integer>(5, TimeUnit.MINUTES) {
                    @Override
                    protected Integer loadValue() {
                        return Jenkins.getInstance().getPluginManager().getFailedPlugins().size();
                    }
                }),
                metric(name("jenkins", "plugins", "withUpdate"), new CachedGauge<Integer>(5, TimeUnit.MINUTES) {
                    @Override
                    protected Integer loadValue() {
                        int count = 0;
                        for (PluginWrapper w : Jenkins.getInstance().getPluginManager().getPlugins()) {
                            if (w.hasUpdate()) {
                                count++;
                            }
                        }
                        return count;
                    }
                }),
                metric(name("jenkins", "runs"), runCounters())
        );
    }

    private MetricSet runCounters() {
        final Map<String, Metric> runCounters = new HashMap<String, Metric>();
        for (String resultName : ResultRunListener.ALL) {
            Meter counter = new Meter();
            jenkinsRunResults.put(resultName, counter);
            runCounters.put(resultName, counter);
        }
        return new MetricSet() {
            public Map<String, Metric> getMetrics() {
                return runCounters;
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
            for (Map.Entry<Computer, Timer> entry : computerBuildDurations.entrySet()) {
                if (forRetention.contains(entry.getKey())) {
                    continue;
                }
                // purge dead nodes
                metricRegistry.remove(name("jenkins", "node", entry.getKey().getName(), "builds"));
            }
            computerBuildDurations.keySet().retainAll(forRetention);
        }

    }

    private synchronized Timer getOrCreateTimer(Computer computer) {
        Timer timer = computerBuildDurations.get(computer);
        if (timer == null) {
            timer = Metrics.metricRegistry().timer(name("jenkins", "node", computer.getName(), "builds"));
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

    private static class JobStats {
        private final int jobCount;
        private final int disabledProjectCount;
        private final int projectCount;


        public JobStats(int jobCount, int projectCount, int disabledProjectCount) {
            this.jobCount = jobCount;
            this.disabledProjectCount = disabledProjectCount;
            this.projectCount = projectCount;
        }

        public int getJobCount() {
            return jobCount;
        }

        public int getDisabledProjectCount() {
            return disabledProjectCount;
        }

        public int getProjectCount() {
            return projectCount;
        }

        public Integer getEnabledProjectCount() {
            return projectCount - disabledProjectCount;
        }
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS
                    .toMillis(5); // the meters expect to be ticked every 5 seconds to give a valid m1, m5 and m15
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
    public static class ResultRunListener extends RunListener<Run> {
        static final String[] ALL = new String[]{
                "success", "unstable", "failure", "not_built", "aborted", "total"};

        @Override
        public synchronized void onCompleted(Run run, TaskListener listener) {
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null) {
                instance.jenkinsRunResults.get(String.valueOf(run.getResult()).toLowerCase()).mark();
                instance.jenkinsRunResults.get("total").mark();
            }
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
                contextList.add(instance.jenkinsJobBuildingTime.time());
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
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null && instance.jenkinsJobBuildingTime != null) {
                instance.jenkinsJobBuildingTime.update(run.getDuration(), TimeUnit.MILLISECONDS);
            }
            List<Timer.Context> contextList = contexts.remove(run);
            if (contextList != null) {
                for (Timer.Context context : contextList) {
                    context.stop();
                }
            }
            TimeInQueueAction action = run.getAction(TimeInQueueAction.class);
            if (action != null && instance != null && instance.jenkinsJobTotalTime != null) {
                instance.jenkinsJobTotalTime
                        .update(run.getDuration() + action.getQueuingDurationMillis(), TimeUnit.MILLISECONDS);
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

        private final Map<WorkUnitContext, TimeInQueueAction> actions = new WeakHashMap<WorkUnitContext,
                TimeInQueueAction>();
        private final Map<Queue.BlockedItem, Timer.Context> blocked =
                new WeakHashMap<Queue.BlockedItem, Timer.Context>();
        private final Map<Queue.BuildableItem, Timer.Context> buildable =
                new WeakHashMap<Queue.BuildableItem, Timer.Context>();
        private final Map<Queue.WaitingItem, Timer.Context> waiting =
                new WeakHashMap<Queue.WaitingItem, Timer.Context>();

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
            TimeInQueueAction action;
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
            if (instance != null && instance.jenkinsJobQueueTime != null) {
                instance.jenkinsJobQueueTime.update(millisecondsInQueue, TimeUnit.MILLISECONDS);
            }
            if (li.outcome != null) {
                synchronized (actions) {
                    actions.put(li.outcome, new TimeInQueueAction(millisecondsInQueue));
                }
            }
        }

        @Override
        public void onEnterBlocked(Queue.BlockedItem bi) {
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsJobBlockedTime != null) {
                synchronized (blocked) {
                    if (!blocked.containsKey(bi)) {
                        blocked.put(bi, instance.jenkinsJobBlockedTime.time());
                    }
                }
            }
        }

        @Override
        public void onLeaveBlocked(Queue.BlockedItem bi) {
            synchronized (blocked) {
                Timer.Context context = blocked.remove(bi);
                if (context != null) {
                    context.stop();
                }
            }
        }

        @Override
        public void onEnterBuildable(Queue.BuildableItem bi) {
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsJobBuildableTime != null) {
                synchronized (buildable) {
                    if (!buildable.containsKey(bi)) {
                        buildable.put(bi, instance.jenkinsJobBuildableTime.time());
                    }
                }
            }
        }

        @Override
        public void onLeaveBuildable(Queue.BuildableItem bi) {
            synchronized (buildable) {
                Timer.Context context = buildable.remove(bi);
                if (context != null) {
                    context.stop();
                }
            }
        }

        @Override
        public void onEnterWaiting(Queue.WaitingItem wi) {
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsJobWaitingTime != null) {
                synchronized (waiting) {
                    if (!waiting.containsKey(wi)) {
                        waiting.put(wi, instance.jenkinsJobWaitingTime.time());
                    }
                }
            }
        }

        @Override
        public void onLeaveWaiting(Queue.WaitingItem wi) {
            synchronized (waiting) {
                Timer.Context context = waiting.remove(wi);
                if (context != null) {
                    context.stop();
                }
            }
        }
    }

}
