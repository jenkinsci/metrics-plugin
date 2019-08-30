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

import hudson.ExtensionList;
import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import jenkins.metrics.api.Metrics;
import jenkins.metrics.api.QueueItemMetricsEvent;
import jenkins.metrics.api.QueueItemMetricsListener;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestExtension;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

public class JenkinsMetricProviderImplTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void given__a_job__when__built__then__events_and_metrics_include_build() throws Exception {
        j.jenkins.setQuietPeriod(0);
        MyListener listener = ExtensionList.lookup(QueueItemMetricsListener.class).get(MyListener.class);
        assertThat(listener, notNullValue());
        FreeStyleProject p = j.createProject(FreeStyleProject.class);
        p.setQuietPeriod(0);
        p.getBuildersList().add(new SleepBuilder(3000));
        assertThat(Metrics.metricRegistry().getTimers().get("jenkins.job.building.duration").getCount(), is(0L));
        assertThat(listener.getEvents(), is(emptyCollectionOf(QueueItemMetricsEvent.class)));
        j.assertBuildStatusSuccess(p.scheduleBuild2(2));
        assertThat(Metrics.metricRegistry().getTimers().get("jenkins.job.building.duration").getCount(), is(1L));
        assertThat(Metrics.metricRegistry().getTimers().get("jenkins.job.building.duration").getSnapshot().getMean(),
                allOf(greaterThan(TimeUnit.MILLISECONDS.toNanos(2500)*1.0),
                        lessThan(TimeUnit.MILLISECONDS.toNanos(3500) * 1.0)));
        List<QueueItemMetricsEvent> events = listener.getEvents();
        assertThat(events.size(), is(3));
        Collections.sort(events, QueueItemMetricsEvent::compareEventSequence);
        assertThat(events.get(0).getState(), is(QueueItemMetricsEvent.State.QUEUED));
        assertThat(events.get(0).getRun(), is(Optional.empty()));
        assertThat(events.get(0).getExecutable(), is(Optional.empty()));
        assertThat(events.get(0).getQueuingTotalMillis(), is(Optional.empty()));
        assertThat(events.get(0).getExecutingMillis(), is(Optional.empty()));

        assertThat(events.get(1).getState(), is(QueueItemMetricsEvent.State.STARTED));
        assertThat(events.get(1).getRun(), is(Optional.of(p.getBuildByNumber(1))));
        assertThat(events.get(1).getExecutable(), is(Optional.of(p.getBuildByNumber(1))));
        assertThat(events.get(1).getQueuingTotalMillis().orElse(null), greaterThan(1500L));
        assertThat(events.get(1).getQueuingWaitingMillis().orElse(null), allOf(greaterThan(1500L), lessThan(2500L + 5000L/*Queue is maintained at least once every 5s*/)));
        assertThat(events.get(1).getExecutingMillis(), is(Optional.empty()));
        assertThat(events.get(1).getExecutorCount().orElse(null), is(1));

        assertThat(events.get(2).getState(), is(QueueItemMetricsEvent.State.FINISHED));
        assertThat(events.get(2).getRun(), is(Optional.of(p.getBuildByNumber(1))));
        assertThat(events.get(2).getExecutable(), is(Optional.of(p.getBuildByNumber(1))));
        assertThat(events.get(2).getQueuingTotalMillis().orElse(null), greaterThan(1500L));
        assertThat(events.get(2).getQueuingWaitingMillis().orElse(null), greaterThan(1500L));
        assertThat(events.get(2).getExecutingMillis().orElse(null), allOf(greaterThan(2500L), lessThan(3500L)));
        assertThat(events.get(2).getExecutorCount().orElse(null), is(1));
    }

    @TestExtension
    public static class MyListener extends QueueItemMetricsListener {
        private final List<QueueItemMetricsEvent> events = new ArrayList<>();

        @Override
        public void onQueued(QueueItemMetricsEvent event) {
            synchronized (events) {
                events.add(event);
            }

        }

        @Override
        public void onCancelled(QueueItemMetricsEvent event) {
            synchronized (events) {
                events.add(event);
            }
        }

        @Override
        public void onStarted(QueueItemMetricsEvent event) {
            synchronized (events) {
                events.add(event);
            }
        }

        @Override
        public void onFinished(QueueItemMetricsEvent event) {
            synchronized (events) {
                events.add(event);
            }
        }

        public List<QueueItemMetricsEvent> getEvents() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

}
