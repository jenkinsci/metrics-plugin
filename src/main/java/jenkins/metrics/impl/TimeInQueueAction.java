/*
 * The MIT License
 *
 * Copyright (c) 2014-2018, CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Util;
import hudson.model.Run;
import hudson.model.queue.SubTask;
import java.io.Serializable;
import java.util.List;
import jenkins.model.RunAction2;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Tracks the time spent in the queue
 */
@ExportedBean
public class TimeInQueueAction implements Serializable, RunAction2 {

    /**
     * Standardize serialization.
     */
    private static final long serialVersionUID = 1L;
    /**
     * How long spent queuing.
     */
    private final long queuingDurationMillis;
    /**
     * How long spent in the queue because blocked.
     */
    private final long blockedDurationMillis;
    /**
     * How long spent in the queue while buildable.
     */
    private final long buildableDurationMillis;
    /**
     * How long spent in the queue while waiting.
     */
    private final long waitingDurationMillis;
    /**
     * The {@link Run}, injected by {@link #onAttached(Run)} or {@link #onLoad(Run)}
     */
    @CheckForNull
    private transient Run<?, ?> run;

    /**
     * Constructor.
     *
     * @param queuingDurationMillis How long spent queuing.
     */
    @Restricted(DoNotUse.class)
    @Deprecated
    public TimeInQueueAction(long queuingDurationMillis) {
        this(queuingDurationMillis, 0L, 0L, 0L);
    }

    /**
     * Constructor.
     *
     * @param millisecondsInQueue     How long spent queuing.
     * @param blockedDurationMillis   How long spent in the queue because blocked.
     * @param buildableDurationMillis How long spent in the queue while buildable.
     * @param waitingDurationMillis   How long spent in the queue while waiting.
     */
    public TimeInQueueAction(long millisecondsInQueue, long blockedDurationMillis, long buildableDurationMillis,
                             long waitingDurationMillis) {
        this.queuingDurationMillis = millisecondsInQueue;
        this.blockedDurationMillis = blockedDurationMillis;
        this.buildableDurationMillis = buildableDurationMillis;
        this.waitingDurationMillis = waitingDurationMillis;
    }

    /**
     * Returns the duration this {@link Run} spent queuing, that is the wall time from when it entered the queue until
     * it left the queue.
     *
     * @return the duration this {@link Run} spent queuing
     */
    @Exported(visibility = 1)
    public long getQueuingDurationMillis() {
        return queuingDurationMillis;
    }

    /**
     * Returns the duration this {@link Run} spent in the queue because it was blocked.
     *
     * @return the duration this {@link Run} spent in the queue because it was blocked.
     */
    @Exported(visibility = 2)
    public long getBlockedDurationMillis() {
        return blockedDurationMillis;
    }

    /**
     * Returns the duration this {@link Run} spent in the queue in a buildable state.
     *
     * @return the duration this {@link Run} spent in the queue in a buildable state.
     */
    @Exported(visibility = 2)
    public long getBuildableDurationMillis() {
        return buildableDurationMillis;
    }

    /**
     * Returns the duration this {@link Run} spent in the queue waiting before it could be considered for execution.
     *
     * @return the duration this {@link Run} spent in the queue waiting before it could be considered for execution.
     */
    @Exported(visibility = 2)
    public long getWaitingDurationMillis() {
        return waitingDurationMillis;
    }

    public boolean isHasSubTasks() {
        return run != null && run.getAction(SubTaskTimeInQueueAction.class) != null;
    }

    /**
     * Returns the total time this {@link Run} spent queuing, including the time spent by subtasks. This is the sum
     * of {@link #getQueuingDurationMillis()} plus all the {@link SubTaskTimeInQueueAction#getQueuingDurationMillis()}.
     *
     * @return the total time this {@link Run} spent queuing
     */
    @Exported(visibility = 1)
    public long getQueuingTimeMillis() {
        if (run == null) {
            return queuingDurationMillis;
        }
        long total = queuingDurationMillis;
        for (SubTaskTimeInQueueAction t : run.getActions(SubTaskTimeInQueueAction.class)) {
            total += t.getQueuingDurationMillis();
        }
        return total;
    }

    /**
     * Returns the total time this {@link Run}, and any associated {@link SubTask}s, spent in the queue because they
     * were blocked.
     *
     * @return the total time this {@link Run}, and any associated {@link SubTask}s, spent in the queue because they
     * were blocked.
     */
    @Exported(visibility = 2)
    public long getBlockedTimeMillis() {
        if (run == null) {
            return blockedDurationMillis;
        }
        long total = blockedDurationMillis;
        for (SubTaskTimeInQueueAction t : run.getActions(SubTaskTimeInQueueAction.class)) {
            total += t.getBlockedDurationMillis();
        }
        return total;
    }

    /**
     * Returns the total time this {@link Run}, and any associated {@link SubTask}s, spent in the queue in a
     * buildable state.
     *
     * @return the total time this {@link Run}, and any associated {@link SubTask}s, spent in the queue in a
     * buildable state.
     */
    @Exported(visibility = 2)
    public long getBuildableTimeMillis() {
        if (run == null) {
            return buildableDurationMillis;
        }
        long total = buildableDurationMillis;
        for (SubTaskTimeInQueueAction t : run.getActions(SubTaskTimeInQueueAction.class)) {
            total += t.getBuildableDurationMillis();
        }
        return total;
    }

    /**
     * Returns the total time this {@link Run}, and any associated {@link SubTask}s, spent in the queue waiting
     * before it could be considered for execution.
     *
     * @return the total time this {@link Run}, and any associated {@link SubTask}s, spent in the queue waiting
     * before it could be considered for execution.
     */
    @Exported(visibility = 2)
    public long getWaitingTimeMillis() {
        if (run == null) {
            return waitingDurationMillis;
        }
        long total = waitingDurationMillis;
        for (SubTaskTimeInQueueAction t : run.getActions(SubTaskTimeInQueueAction.class)) {
            total += t.getWaitingDurationMillis();
        }
        return total;
    }

    /**
     * Returns the duration this {@link Run} spent building, that is the wall time from when it left the queue until
     * it was finished.
     *
     * @return the duration this {@link Run} spent building
     */
    @Exported(visibility = 2)
    public long getExecutingTimeMillis() {
        if (run == null) {
            return 0L;
        }
        List<SubTaskTimeInQueueAction> actions = run.getActions(SubTaskTimeInQueueAction.class);
        if (actions.isEmpty()) {
            return run.getDuration();
        }
        long total = 0L;
        for (SubTaskTimeInQueueAction t : actions) {
            total += t.getExecutingDurationMillis() * t.getWorkUnitCount();
        }
        return total;
    }

    @Exported(visibility = 2)
    public long getBuildingDurationMillis() {
        return (run == null ? 0L : run.getDuration());
    }

    /**
     * Returns total duration for this {@link Run}, that is the wall time from when it entered the queue until it was
     * finished.
     *
     * @return total duration for this {@link Run}, that is the wall time from when it entered the queue until it was
     * finished.
     */
    @Exported(visibility = 1)
    public long getTotalDurationMillis() {
        return queuingDurationMillis + getBuildingDurationMillis();
    }

    @Exported(visibility = 2)
    public double getExecutorUtilization() {
        long buildingDurationMillis = getBuildingDurationMillis();
        // the result should be rounded to 2 decimals
        return buildingDurationMillis > 0 ? Math.round(getExecutingTimeMillis() * 100.0 / buildingDurationMillis) / 100.0 : 1.0;
    }

    @Exported(visibility = 2)
    public int getSubTaskCount() {
        if (run == null) {
            return 0;
        }
        return run.getActions(SubTaskTimeInQueueAction.class).size();
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public Run getRun() {
        return run;
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getQueuingDurationString() {
        return Util.getTimeSpanString(getQueuingDurationMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getQueuingTimeString() {
        return Util.getTimeSpanString(getQueuingTimeMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getBlockedTimeString() {
        return Util.getTimeSpanString(getBlockedTimeMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getBuildableTimeString() {
        return Util.getTimeSpanString(getBuildableTimeMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getWaitingTimeString() {
        return Util.getTimeSpanString(getWaitingTimeMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getBlockedDurationString() {
        return Util.getTimeSpanString(getBlockedDurationMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getBuildableDurationString() {
        return Util.getTimeSpanString(getBuildableDurationMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getWaitingDurationString() {
        return Util.getTimeSpanString(getWaitingDurationMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getExecutingTimeString() {
        return Util.getTimeSpanString(getExecutingTimeMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getBuildingDurationString() {
        return Util.getTimeSpanString(getBuildingDurationMillis());
    }

    @Restricted(DoNotUse.class)
    @SuppressWarnings("unused") // stapler EL binding only
    public String getTotalDurationString() {
        return Util.getTimeSpanString(getTotalDurationMillis());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getIconFileName() {
        return "/plugin/metrics/images/24x24/clock.png";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        return "Timings";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUrlName() {
        return "timings";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onAttached(Run<?, ?> r) {
        run = r;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onLoad(Run<?, ?> r) {
        run = r;
    }

}
