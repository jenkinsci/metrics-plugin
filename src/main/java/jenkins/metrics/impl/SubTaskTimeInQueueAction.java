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
import hudson.model.queue.WorkUnitContext;
import java.io.Serializable;

/**
 * Tracks the time occupied by subtasks.
 */
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
     * Constructor
     *
     * @param queuingDurationMillis How long spent queuing.
     */
    public SubTaskTimeInQueueAction(long queuingDurationMillis) {
        this.queuingDurationMillis = queuingDurationMillis;
    }

    /**
     * How long spent queuing (this is the time from when the {@link WorkUnitContext#item} entered the queue until
     * {@link WorkUnitContext#synchronizeStart()} was called.
     */
    public long getQueuingDurationMillis() {
        return queuingDurationMillis;
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
