/*
 * The MIT License
 *
 * Copyright (c) 2013-2018, CloudBees, Inc.
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
import com.codahale.metrics.DerivativeGauge;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.listeners.RunListener;
import hudson.model.queue.QueueListener;
import hudson.model.queue.WorkUnit;
import hudson.model.queue.WorkUnitContext;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.DaemonThreadFactory;
import hudson.util.ExceptionCatchingThreadFactory;
import hudson.util.NamingThreadFactory;
import hudson.util.VersionNumber;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jenkins.metrics.api.MetricProvider;
import jenkins.metrics.api.Metrics;
import jenkins.metrics.api.QueueItemMetricsEvent;
import jenkins.metrics.api.QueueItemMetricsListener;
import jenkins.metrics.util.AutoSamplingHistogram;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Provides Jenkins specific metrics.
 */
@Extension
public class JenkinsMetricProviderImpl extends MetricProvider {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(JenkinsMetricProviderImpl.class.getName());
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
    private Map<Computer, Timer> computerBuildDurations = new WeakHashMap<>();
    /**
     * The rate at which jobs are being scheduled.
     */
    private Meter jenkinsJobScheduleRate;
    /**
     * The amount of time jobs stay in the queue.
     */
    private Timer jenkinsJobQueueDuration;
    /**
     * The amount of time a job is waiting for its quiet period to expire.
     */
    private Timer jenkinsJobWaitingDuration;
    /**
     * The amount of time jobs are blocked waiting for a resource that has a restricted sharing policy.
     */
    private Timer jenkinsJobBlockedDuration;
    /**
     * The amount of time jobs are buildable and waiting for an executor.
     */
    private Timer jenkinsJobBuildableDuration;
    /**
     * The amount of time jobs are building (start to finish).
     */
    private Timer jenkinsJobBuildingDuration;
    /**
     * The rate at which tasks are being scheduled.
     */
    private Meter jenkinsTaskScheduleRate;
    /**
     * The amount of time tasks stay in the queue.
     */
    private Timer jenkinsTaskQueueDuration;
    /**
     * The amount of time a task is waiting for its quiet period to expire.
     */
    private Timer jenkinsTaskWaitingDuration;
    /**
     * The amount of time tasks are blocked waiting for a resource that has a restricted sharing policy.
     */
    private Timer jenkinsTaskBlockedDuration;
    /**
     * The amount of time tasks are buildable and waiting for an executor.
     */
    private Timer jenkinsTaskBuildableDuration;
    /**
     * The amount of time tasks are executing.
     */
    private Timer jenkinsTaskExecutionDuration;
    /**
     * The amount of time jobs are execute (cumulative).
     */
    private Timer jenkinsJobExecutionTime;
    /**
     * Run Results.
     */
    private HashMap<String, Meter> jenkinsRunResults = new HashMap<>();
    /**
     * The amount of time jobs take from initial scheduling to completion.
     */
    private Timer jenkinsJobTotalDuration;

    public JenkinsMetricProviderImpl() {
        Gauge<QueueStats> jenkinsQueue = new CachedGauge<QueueStats>(1, TimeUnit.SECONDS) {
            @Override
            protected QueueStats loadValue() {
                try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
                    Queue queue = Jenkins.getInstance().getQueue();
                    int length = 0;
                    int blocked = 0;
                    int buildable = 0;
                    int pending = queue == null ? 0 : queue.getPendingItems().size();
                    int stuck = 0;
                    if (queue != null) {
                        for (Queue.Item i : queue.getItems()) {
                            if (i != null) {
                                length++;
                                try {
                                    if (i.isBlocked()) {
                                        blocked++;
                                    }
                                    if (i.isBuildable()) {
                                        buildable++;
                                    }
                                    if (i.isStuck()) {
                                        stuck++;
                                    }
                                } catch (Exception e) {
                                    LOGGER.log(Level.FINE, "Uncaught exception recording queue statistics", e);
                                } catch (OutOfMemoryError e) {
                                    throw e;
                                } catch (Throwable e) {
                                    LOGGER.log(Level.FINE, "Uncaught throwable recording queue statistics", e);
                                }
                            }
                        }
                    }
                    return new QueueStats(length, blocked, buildable, pending, stuck);
                }
            }
        };
        Gauge<NodeStats> jenkinsNodes = new

                CachedGauge<NodeStats>(1, TimeUnit.SECONDS) {
                    @Override
                    protected NodeStats loadValue() {
                        try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
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
                    }
                };
        Gauge<JobStats> jobStats = new CachedGauge<JobStats>(5, TimeUnit.MINUTES) {
            @Override
            protected JobStats loadValue() {
                try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
                    int count = 0;
                    int disabledProjects = 0;
                    int projectCount = 0;
                    long depthTotal = 0;
                    Stack<ItemGroup> q = new Stack<ItemGroup>();
                    q.push(Jenkins.getInstance());
                    while (!q.isEmpty()) {
                        ItemGroup<?> parent = q.pop();
                        int depth = 0;
                        ItemGroup p = parent;
                        while (p != null) {
                            depth++;
                            p = p instanceof Item ? ((Item) p).getParent() : null;
                        }
                        for (Item i : parent.getItems()) {
                            if (!(i instanceof TopLevelItem)) {
                                continue;
                            }
                            if (i instanceof Job) {
                                count++;
                                depthTotal += depth;
                                if (i instanceof AbstractProject) {
                                    projectCount++;
                                    if (((AbstractProject) i).isDisabled()) {
                                        disabledProjects++;
                                    }
                                }
                            }
                            if (i instanceof ItemGroup) {
                                q.push((ItemGroup) i);
                            }
                        }
                    }
                    return new JobStats(count, projectCount, disabledProjects,
                            count == 0 ? 0.0 : depthTotal / ((double) count));
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
                metric(name("jenkins", "task", "scheduled"), (jenkinsTaskScheduleRate = new Meter())),
                metric(name("jenkins", "job", "count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats, Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getJobCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "job", "averageDepth"),
                        new DerivativeGauge<JobStats, Double>(jobStats) {
                            @Override
                            protected Double transform(JobStats value) {
                                return value.getDepthAverage();
                            }
                        }),
                metric(name("jenkins", "project", "count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats, Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getProjectCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "project", "enabled", "count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats, Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getEnabledProjectCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "project", "disabled", "count"),
                        new AutoSamplingHistogram(new DerivativeGauge<JobStats, Integer>(jobStats) {
                            @Override
                            protected Integer transform(JobStats value) {
                                return value.getDisabledProjectCount();
                            }
                        }).toMetricSet()),
                metric(name("jenkins", "job", "queuing", "duration"), (jenkinsJobQueueDuration = new Timer())),
                metric(name("jenkins", "job", "waiting", "duration"), (jenkinsJobWaitingDuration = new Timer())),
                metric(name("jenkins", "job", "blocked", "duration"), (jenkinsJobBlockedDuration = new Timer())),
                metric(name("jenkins", "job", "buildable", "duration"), (jenkinsJobBuildableDuration = new Timer())),
                metric(name("jenkins", "job", "building", "duration"), (jenkinsJobBuildingDuration = new Timer())),
                metric(name("jenkins", "job", "execution", "time"), (jenkinsJobExecutionTime = new Timer())),
                metric(name("jenkins", "task", "queuing", "duration"), (jenkinsTaskQueueDuration = new Timer())),
                metric(name("jenkins", "task", "waiting", "duration"), (jenkinsTaskWaitingDuration = new Timer())),
                metric(name("jenkins", "task", "blocked", "duration"), (jenkinsTaskBlockedDuration = new Timer())),
                metric(name("jenkins", "task", "buildable", "duration"), (jenkinsTaskBuildableDuration = new Timer())),
                metric(name("jenkins", "task", "execution", "duration"), (jenkinsTaskExecutionDuration = new Timer())),
                metric(name("jenkins", "job", "total", "duration"), (jenkinsJobTotalDuration = new Timer())),
                metric(name("jenkins", "plugins", "active"), new CachedGauge<Integer>(5, TimeUnit.MINUTES) {
                    @Override
                    protected Integer loadValue() {
                        int count = 0;
                        Jenkins jenkins = Jenkins.getInstance();
                        for (PluginWrapper w : jenkins.getPluginManager().getPlugins()) {
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
                        Jenkins jenkins = Jenkins.getInstance();
                        for (PluginWrapper w : jenkins.getPluginManager().getPlugins()) {
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
                        Jenkins jenkins = Jenkins.getInstance();
                        return jenkins.getPluginManager().getFailedPlugins().size();
                    }
                }),
                metric(name("jenkins", "plugins", "withUpdate"), new CachedGauge<Integer>(5, TimeUnit.MINUTES) {
                    @Override
                    protected Integer loadValue() {
                        int count = 0;
                        Jenkins jenkins = Jenkins.getInstance();
                        for (PluginWrapper w : jenkins.getPluginManager().getPlugins()) {
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

    private static boolean computerBuildDurationTimers(String s, Metric metric) {
        return s.startsWith("jenkins.node.")
                && s.endsWith(".builds")
                && s.length() > ("jenkins.node." + ".builds").length()
                && metric instanceof Timer;
    }

    private MetricSet runCounters() {
        final Map<String, Metric> runCounters = new HashMap<>();
        for (String resultName : ResultRunListener.ALL) {
            Meter counter = new Meter();
            jenkinsRunResults.put(resultName, counter);
            runCounters.put(resultName, counter);
        }
        return () -> runCounters;
    }

    public static JenkinsMetricProviderImpl instance() {
        return ExtensionList.lookup(MetricProvider.class).get(JenkinsMetricProviderImpl.class);
    }

    /**
     * {@inheritDoc}
     */
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
        Set<String> nodeMetricNames = new HashSet<>();
        for (Node node : jenkins.getNodes()) {
            nodeMetricNames.add(name("jenkins", "node", node.getNodeName(), "builds"));
            Computer computer = node.toComputer();
            if (computer != null) {
                getOrCreateTimer(computer);
            }
        }
        MetricRegistry metricRegistry = Metrics.metricRegistry();
        metricRegistry.getTimers(JenkinsMetricProviderImpl::computerBuildDurationTimers)
                .keySet()
                .stream()
                .filter(name -> !nodeMetricNames.contains(name))
                .forEach(metricRegistry::remove);
    }

    private synchronized Timer getOrCreateTimer(Computer computer) {
        return computerBuildDurations.computeIfAbsent(computer,
                c -> Metrics.metricRegistry().timer(name("jenkins", "node", c.getName(), "builds")));
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
        private final double depthAverage;


        public JobStats(int jobCount, int projectCount, int disabledProjectCount, double depthAverage) {
            this.jobCount = jobCount;
            this.disabledProjectCount = disabledProjectCount;
            this.projectCount = projectCount;
            this.depthAverage = depthAverage;
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

        public double getDepthAverage() {
            return depthAverage;
        }
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {

        /**
         * {@inheritDoc}
         */
        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS
                    .toMillis(5); // the meters expect to be ticked every 5 seconds to give a valid m1, m5 and m15
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected synchronized void doRun() {
            final JenkinsMetricProviderImpl instance = instance();
            if (instance == null) {
                return;
            }
            instance.updateMetrics();
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
                    PeriodicWork p = ExtensionList.lookup(PeriodicWork.class).get(PeriodicWorkImpl.class);
                    if (p != null) {
                        jenkins.util.Timer.get().scheduleAtFixedRate(p, p.getInitialDelay(), p.getRecurrencePeriod(),
                                TimeUnit.MILLISECONDS);
                    }
                }
            }
        }

    }

    @Extension
    public static class ResultRunListener extends RunListener<Run> {
        static final String[] ALL = new String[]{
                "success", "unstable", "failure", "not_built", "aborted", "total"
        };

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void onCompleted(Run run, TaskListener listener) {
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null) {
                instance.jenkinsRunResults.get(String.valueOf(run.getResult()).toLowerCase(Locale.ENGLISH)).mark();
                instance.jenkinsRunResults.get("total").mark();
            }
        }

    }

    @Extension
    public static class RunListenerImpl extends RunListener<Run> {
        private Map<Run, List<Timer.Context>> contexts = new HashMap<>();

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void onStarted(Run run, TaskListener listener) {
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null) {
                List<Timer.Context> contextList = new ArrayList<>();
                contextList.add(instance.jenkinsJobBuildingDuration.time());
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

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void onCompleted(Run run, TaskListener listener) {
            List<Timer.Context> contextList = contexts.remove(run);
            if (contextList != null) {
                for (Timer.Context context : contextList) {
                    context.stop();
                }
            }
            JenkinsMetricProviderImpl instance = instance();
            TimeInQueueAction action = run.getAction(TimeInQueueAction.class);
            if (action != null && instance != null) {
                if (instance.jenkinsJobQueueDuration != null) {
                    instance.jenkinsJobQueueDuration.update(action.getQueuingTimeMillis(), TimeUnit.MILLISECONDS);
                }
                if (instance.jenkinsJobBlockedDuration != null) {
                    instance.jenkinsJobBlockedDuration.update(action.getBlockedTimeMillis(), TimeUnit.MILLISECONDS);
                }
                if (instance.jenkinsJobBuildableDuration != null) {
                    instance.jenkinsJobBuildableDuration.update(action.getBuildableTimeMillis(), TimeUnit.MILLISECONDS);
                }
                if (instance.jenkinsJobWaitingDuration != null) {
                    instance.jenkinsJobWaitingDuration.update(action.getWaitingTimeMillis(), TimeUnit.MILLISECONDS);
                }
                if (instance.jenkinsJobTotalDuration != null) {
                    instance.jenkinsJobTotalDuration.update(action.getTotalDurationMillis(), TimeUnit.MILLISECONDS);
                }
                if (instance.jenkinsJobExecutionTime != null) {
                    instance.jenkinsJobExecutionTime.update(action.getExecutingTimeMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    @Extension(ordinal = Double.MAX_VALUE)
    public static class SchedulingRate extends Queue.QueueDecisionHandler {

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
            JenkinsMetricProviderImpl instance = instance();
            if (instance != null) {
                if (p instanceof Job && instance.jenkinsJobScheduleRate != null) {
                    instance.jenkinsJobScheduleRate.mark();
                }
                if (instance.jenkinsTaskScheduleRate != null) {
                    instance.jenkinsTaskScheduleRate.mark();
                }
            }
            return true;
        }
    }

    @Extension
    public static class ScheduledRate extends QueueListener {

        /**
         * How often we trim the {@link ScheduledRate#totals} map.
         */
        private static final long TRIM_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

        /**
         * Our executor service for tracing queue subtasks.
         */
        private transient ExecutorService executorService = Executors
                .newCachedThreadPool(
                        new NamingThreadFactory(new ExceptionCatchingThreadFactory(new DaemonThreadFactory()),
                                "QueueSubTaskMetrics"));
        private final Map<WorkUnitContext, TimeInQueueAction> actions = new WeakHashMap<>();
        private final Map<Queue.BlockedItem, Timer.Context> blocked = new WeakHashMap<>();
        private final Map<Queue.BuildableItem, Timer.Context> buildable = new WeakHashMap<>();
        private final Map<Queue.WaitingItem, Timer.Context> waiting = new WeakHashMap<>();
        private final AtomicLong nextTrim = new AtomicLong(System.nanoTime());
        private final ConcurrentMap<Long, ItemTotals> totals = new ConcurrentHashMap<>();
        private Set<Long> previousIds = new HashSet<>();

        public static ScheduledRate instance() {
            return ExtensionList.lookup(QueueListener.class).get(ScheduledRate.class);
        }

        private void trim() {
            long next = nextTrim.get();
            if (next - System.nanoTime() < 0L && nextTrim.compareAndSet(next, next + TRIM_INTERVAL_NANOS)) {
                // we won the trim lottery
                Queue queue = Jenkins.getInstance().getQueue();
                // get the IDs that are currently known
                Set<Long> currentIds = Stream.concat(Stream.of(queue.getItems()), queue.getLeftItems().stream())
                        .map(Queue.Item::getId)
                        .collect(Collectors.toCollection(HashSet::new));
                // remove the currently known IDs from the previous list of known IDs
                previousIds.removeAll(currentIds);
                // now remove from the totals the ones that were previously known (at last trim()) but are
                // no longer known. We need to do it this was as otherwise any IDs that are added while
                // we trim() would be missing from the currentIds and hence the naive
                //   totals.keySet().removeAll(currentIds)
                // would mean we lose full tracking for any items scheduled during a trim()
                totals.keySet().removeAll(previousIds);
                previousIds = currentIds;
            }
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
            final long leftQueueAt = System.currentTimeMillis();
            long millisecondsInQueue = leftQueueAt - li.getInQueueSince();
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsTaskQueueDuration != null) {
                instance.jenkinsTaskQueueDuration.update(millisecondsInQueue, TimeUnit.MILLISECONDS);
            }
            ItemTotals t = totals.getOrDefault(li.getId(), ItemTotals.EMPTY);
            final WorkUnitContext wuc = li.outcome;
            if (wuc != null) {
                synchronized (actions) {
                    actions.put(wuc, new TimeInQueueAction(
                            millisecondsInQueue,
                            TimeUnit.NANOSECONDS.toMillis(t.blocked.get()),
                            TimeUnit.NANOSECONDS.toMillis(t.buildable.get()),
                            TimeUnit.NANOSECONDS.toMillis(t.waiting.get())
                    ));
                }
                Queue.Task owner = li.task.getOwnerTask();
                while (owner != owner.getOwnerTask()) {
                    owner = owner.getOwnerTask();
                }
                boolean subTask = owner != li.task;
                CompletableFuture.supplyAsync(asSupplier(wuc.future.getStartCondition()), executorService)
                        .thenAccept((executable) -> {
                            long startTimeMillis = System.currentTimeMillis();
                            long queuingDurationMillis = startTimeMillis - li.getInQueueSince();
                            QueueItemMetricsListener.notifyStarted(new QueueItemMetricsEvent(
                                    li,
                                    QueueItemMetricsEvent.State.STARTED,
                                    RunResolver.resolve(executable).orElse(null),
                                    executable,
                                    queuingDurationMillis,
                                    TimeUnit.NANOSECONDS.toMillis(t.waiting.get()),
                                    TimeUnit.NANOSECONDS.toMillis(t.blocked.get()),
                                    TimeUnit.NANOSECONDS.toMillis(t.buildable.get()),
                                    null,
                                    wuc.getWorkUnits().size())
                            );
                            CompletableFuture.supplyAsync(asSupplier(wuc.future), executorService)
                                    .thenRun(() -> {
                                        long executionDurationMillis =
                                                System.currentTimeMillis() - startTimeMillis;
                                        // the run resolver may only work *after* the executable finished
                                        // as it may rely on state that gets inferred during the start
                                        // thus we re-resolve after the executable finished
                                        Optional<Run<?, ?>> run = RunResolver.resolve(executable);
                                        if (subTask) {
                                            run.ifPresent(r -> r.addAction(new SubTaskTimeInQueueAction(
                                                    queuingDurationMillis,
                                                    TimeUnit.NANOSECONDS.toMillis(t.blocked.get()),
                                                    TimeUnit.NANOSECONDS.toMillis(t.buildable.get()),
                                                    TimeUnit.NANOSECONDS.toMillis(t.waiting.get()),
                                                    executionDurationMillis,
                                                    wuc.getWorkUnits().size()
                                            )));
                                        }
                                        QueueItemMetricsListener.notifyFinished(new QueueItemMetricsEvent(
                                                        li,
                                                        QueueItemMetricsEvent.State.FINISHED,
                                                        run.orElse(null),
                                                        executable,
                                                        queuingDurationMillis,
                                                        TimeUnit.NANOSECONDS.toMillis(t.waiting.get()),
                                                        TimeUnit.NANOSECONDS.toMillis(t.blocked.get()),
                                                        TimeUnit.NANOSECONDS.toMillis(t.buildable.get()),
                                                        executionDurationMillis,
                                                        wuc.getWorkUnits().size()
                                                )
                                        );
                                        if (instance != null
                                                && instance.jenkinsTaskExecutionDuration != null) {
                                            instance.jenkinsTaskExecutionDuration.update(
                                                    executionDurationMillis, TimeUnit.MILLISECONDS
                                            );
                                        }
                                    });
                        });
            } else {
                QueueItemMetricsEvent m = new QueueItemMetricsEvent(
                        li,
                        QueueItemMetricsEvent.State.CANCELLED,
                        null,
                        null,
                        System.currentTimeMillis() - li.getInQueueSince(),
                        TimeUnit.NANOSECONDS.toMillis(t.blocked.get()),
                        TimeUnit.NANOSECONDS.toMillis(t.buildable.get()),
                        TimeUnit.NANOSECONDS.toMillis(t.waiting.get()),
                        null,
                        null
                );
                executorService.submit(()-> QueueItemMetricsListener.notifyCancelled(m));
            }
            totals.remove(li.getId());
            trim();
        }

        private void checkEnterQueue(Queue.Item i) {
            totals.computeIfAbsent(i.getId(), id -> {
                QueueItemMetricsEvent m = new QueueItemMetricsEvent(
                        i,
                        QueueItemMetricsEvent.State.QUEUED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
                executorService.submit(() -> {
                    QueueItemMetricsListener.notifyStarted(m);
                });
                return new ItemTotals(id);
            });
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onEnterBlocked(Queue.BlockedItem bi) {
            checkEnterQueue(bi);
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsTaskBlockedDuration != null) {
                synchronized (blocked) {
                    if (!blocked.containsKey(bi)) {
                        blocked.put(bi, instance.jenkinsTaskBlockedDuration.time());
                    }
                }
            }
            trim();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onLeaveBlocked(Queue.BlockedItem bi) {
            synchronized (blocked) {
                Timer.Context context = blocked.remove(bi);
                if (context != null) {
                    totals.computeIfAbsent(bi.getId(), ItemTotals::new).blocked.getAndAdd(context.stop());
                }
            }
            trim();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onEnterBuildable(Queue.BuildableItem bi) {
            checkEnterQueue(bi);
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsTaskBuildableDuration != null) {
                synchronized (buildable) {
                    buildable.computeIfAbsent(bi, (x) -> instance.jenkinsTaskBuildableDuration.time());
                }
            }
            trim();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onLeaveBuildable(Queue.BuildableItem bi) {
            synchronized (buildable) {
                Timer.Context context = buildable.remove(bi);
                if (context != null) {
                    totals.computeIfAbsent(bi.getId(), ItemTotals::new).buildable.getAndAdd(context.stop());
                }
            }
            trim();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onEnterWaiting(Queue.WaitingItem wi) {
            checkEnterQueue(wi);
            JenkinsMetricProviderImpl instance = JenkinsMetricProviderImpl.instance();
            if (instance != null && instance.jenkinsTaskWaitingDuration != null) {
                synchronized (waiting) {
                    waiting.computeIfAbsent(wi, (x) -> instance.jenkinsTaskWaitingDuration.time());
                }
            }
            trim();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onLeaveWaiting(Queue.WaitingItem wi) {
            synchronized (waiting) {
                Timer.Context context = waiting.remove(wi);
                if (context != null) {
                    totals.computeIfAbsent(wi.getId(), ItemTotals::new).waiting.getAndAdd(context.stop());
                }
            }
            trim();
        }

        private static class ItemTotals {

            private static final ItemTotals EMPTY = new ItemTotals(null);

            private final AtomicLong blocked = new AtomicLong();
            private final AtomicLong buildable = new AtomicLong();
            private final AtomicLong waiting = new AtomicLong();

            private ItemTotals(Long ignore) {

            }

            @Override
            public String toString() {
                return "ItemTotals{" +
                        "blocked=" + Util.getTimeSpanString(TimeUnit.NANOSECONDS.toMillis(blocked.get())) +
                        ", buildable=" + Util.getTimeSpanString(TimeUnit.NANOSECONDS.toMillis(buildable.get())) +
                        ", waiting=" + Util.getTimeSpanString(TimeUnit.NANOSECONDS.toMillis(waiting.get())) +
                        '}';
            }
        }
    }

    private static <V> Supplier<V> asSupplier(Future<V> future) {
        return () -> {
            try {
                return future.get();
            } catch (Throwable t) {
                sneakyThrow(t);
                throw new RuntimeException(t);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
