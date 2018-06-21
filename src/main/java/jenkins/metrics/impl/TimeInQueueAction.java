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
import java.io.Serializable;
import jenkins.model.RunAction2;
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
     * The {@link Run}, injected by {@link #onAttached(Run)} or {@link #onLoad(Run)}
     */
    @CheckForNull
    private transient Run<?, ?> run;

    /**
     * Constructor.
     *
     * @param queuingDurationMillis
     */
    public TimeInQueueAction(long queuingDurationMillis) {
        this.queuingDurationMillis = queuingDurationMillis;
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
     * Returns the duration this {@link Run} spent building, that is the wall time from when it left the queue until
     * it was finished.
     *
     * @return the duration this {@link Run} spent building
     */
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

    public String getQueuingDurationString() {
        return Util.getTimeSpanString(getQueuingDurationMillis());
    }

    public String getQueuingTimeString() {
        return Util.getTimeSpanString(getQueuingTimeMillis());
    }

    public String getBuildingDurationString() {
        return Util.getTimeSpanString(getBuildingDurationMillis());
    }

    public String getTotalDurationString() {
        return Util.getTimeSpanString(getTotalDurationMillis());
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
