/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
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

import hudson.model.Action;
import hudson.model.Queue;
import hudson.model.queue.SubTask;
import hudson.model.queue.WorkUnitContext;
import java.io.Serializable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Tracks the time occupied by subtasks.
 */
@Restricted(NoExternalUse.class) // may want to expose at a later time, but for now keep private
public class SubTaskTimeInQueueAction implements Serializable, Action {

    /**
     * Standardize serialization.
     */
    private static final long serialVersionUID = 1L;
    /**
     * How long spent queuing (this is the time from when the {@link WorkUnitContext#item} entered the queue until
     * {@link WorkUnitContext#synchronizeStart()} was called.
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
     * How long spent executing (this is the time from when {@link WorkUnitContext#synchronizeStart()} was called
     * until {@link WorkUnitContext#synchronizeEnd(Queue.Executable, Throwable, long)} was called. The total executor
     * CPU time is this multiplied by {@link #workUnitCount}.
     */
    private final long executingDurationMillis;
    /**
     * This is the size of {@link WorkUnitContext#getWorkUnits()}.
     */
    private final int workUnitCount;

    /**
     * Constructor
     *
     * @param queuingDurationMillis How long spent queuing.
     */
    public SubTaskTimeInQueueAction(long queuingDurationMillis,
                                    long blockedDurationMillis,
                                    long buildableDurationMillis,
                                    long waitingDurationMillis,
                                    long executingDurationMillis,
                                    int workUnitCount) {
        this.queuingDurationMillis = queuingDurationMillis;
        this.blockedDurationMillis = blockedDurationMillis;
        this.buildableDurationMillis = buildableDurationMillis;
        this.waitingDurationMillis = waitingDurationMillis;
        this.executingDurationMillis = executingDurationMillis;
        this.workUnitCount = workUnitCount;
    }

    /**
     * How long spent queuing (this is the time from when the {@link WorkUnitContext#item} entered the queue until
     * {@link WorkUnitContext#synchronizeStart()} was called.
     */
    public long getQueuingDurationMillis() {
        return queuingDurationMillis;
    }

    /**
     * Returns the duration this {@link SubTask} spent in the queue because it was blocked.
     *
     * @return the duration this {@link SubTask} spent in the queue because it was blocked.
     */
    public long getBlockedDurationMillis() {
        return blockedDurationMillis;
    }

    /**
     * Returns the duration this {@link SubTask} spent in the queue in a buildable state.
     *
     * @return the duration this {@link SubTask} spent in the queue in a buildable state.
     */
    public long getBuildableDurationMillis() {
        return buildableDurationMillis;
    }

    /**
     * Returns the duration this {@link SubTask} spent in the queue waiting before it could be considered for execution.
     *
     * @return the duration this {@link SubTask} spent in the queue waiting before it could be considered for execution.
     */
    public long getWaitingDurationMillis() {
        return waitingDurationMillis;
    }

    /**
     * Returns the duration this {@link SubTask} spent executing.
     *
     * @return the duration this {@link SubTask} spent executing.
     */
    public long getExecutingDurationMillis() {
        return executingDurationMillis;
    }

    /**
     * Returns the number of executor slots occupied by this {@link SubTask}.
     *
     * @return the number of executor slots occupied by this {@link SubTask}.
     */
    public int getWorkUnitCount() {
        return workUnitCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getIconFileName() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getUrlName() {
        return null;
    }
}
