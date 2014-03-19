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

package jenkins.metrics.impl;

import hudson.Util;
import hudson.model.Run;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.Serializable;

/**
 * @author Stephen Connolly
 */
@ExportedBean
public class TimeInQueueAction implements Serializable, RunAction2 {

    private static final long serialVersionUID = 1L;
    private final long queuingDurationMillis;
    private transient Run<?, ?> run;

    public TimeInQueueAction(long queuingDurationMillis) {
        this.queuingDurationMillis = queuingDurationMillis;
    }

    @Exported(visibility = 1)
    public long getQueuingDurationMillis() {
        return queuingDurationMillis;
    }

    public long getBuildingDurationMillis() {
        return (run == null ? 0L : run.getDuration());
    }

    @Exported(visibility = 1)
    public long getTotalDurationMillis() {
        return queuingDurationMillis + getBuildingDurationMillis();
    }

    public String getQueuingDurationString() {
        return Util.getTimeSpanString(getQueuingDurationMillis());
    }

    public String getBuildingDurationString() {
        return Util.getTimeSpanString(getBuildingDurationMillis());
    }

    public String getTotalDurationString() {
        return Util.getTimeSpanString(getTotalDurationMillis());
    }

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return "Timings";
    }

    public String getUrlName() {
        return "timings";
    }

    public void onAttached(Run<?, ?> r) {
        run = r;
    }

    public void onLoad(Run<?, ?> r) {
        run = r;
    }
}
