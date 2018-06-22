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

package jenkins.metrics.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Holds the metrics about a queue item.
 */
public final class QueueItemMetricsEvent {
    /**
     * A timestamp used to allow comparison of metrics.
     */
    private final long eventTick = System.nanoTime();
    @NonNull
    private final Queue.Item item;
    @CheckForNull
    private final Label assignedLabel;
    @NonNull
    private final State state;
    @CheckForNull
    private final Run<?, ?> run;
    @CheckForNull
    private final Queue.Executable executable;
    @CheckForNull
    private final List<Set<LabelAtom>> consumedLabelAtoms;
    @CheckForNull
    private final Long queuingTotalMillis;
    @CheckForNull
    private final Long queuingWaitingMillis;
    @CheckForNull
    private final Long queuingBlockedMillis;
    @CheckForNull
    private final Long queuingBuildableMillis;
    @CheckForNull
    private final Long executingMillis;
    @CheckForNull
    private final Integer executorCount;

    public QueueItemMetricsEvent(@NonNull Queue.Item item,
                                 @CheckForNull Label assignedLabel,
                                 @NonNull State state,
                                 @CheckForNull Run<?, ?> run,
                                 @CheckForNull Queue.Executable executable,
                                 @CheckForNull List<Set<LabelAtom>> consumedLabelAtoms,
                                 @CheckForNull Long queuingTotalMillis,
                                 @CheckForNull Long queuingWaitingMillis,
                                 @CheckForNull Long queuingBlockedMillis,
                                 @CheckForNull Long queuingBuildableMillis,
                                 @CheckForNull Long executingMillis,
                                 @CheckForNull Integer executorCount) {
        this.item = item;
        this.assignedLabel = assignedLabel;
        this.state = state;
        this.run = run;
        this.executable = executable;
        this.consumedLabelAtoms = consumedLabelAtoms;
        this.queuingTotalMillis = queuingTotalMillis;
        this.queuingWaitingMillis = queuingWaitingMillis;
        this.queuingBlockedMillis = queuingBlockedMillis;
        this.queuingBuildableMillis = queuingBuildableMillis;
        this.executingMillis = executingMillis;
        this.executorCount = executorCount;
    }

    /**
     * Sorts {@link QueueItemMetricsEvent}s by {@link #getEventTick()}.
     *
     * @param x the first {@code QueueItemMetricsEvent} to compare
     * @param y the second {@code QueueItemMetricsEvent} to compare
     * @return the value {@code 0} if both events happend at the same time;
     * a value less than {@code 0} if {@code x} happened before {@code y}; and
     * a value greater than {@code 0} if {@code y} happened before {@code x}
     */
    public static int compareEventSequence(QueueItemMetricsEvent x, QueueItemMetricsEvent y) {
        return Long.compare(x.getEventTick() - y.getEventTick(), 0L);
    }

    /**
     * Sorts {@link QueueItemMetricsEvent}s by the order {@link Queue.Item#getId()}.
     *
     * @param x the first {@code QueueItemMetricsEvent} to compare
     * @param y the second {@code QueueItemMetricsEvent} to compare
     * @return the value {@code 0} if both events happend at the same time;
     * a value less than {@code 0} if {@code x} was enqueued before {@code y}; and
     * a value greater than {@code 0} if {@code y} was enqueued before {@code x}
     */
    public static int compareQueueSequence(QueueItemMetricsEvent x, QueueItemMetricsEvent y) {
        return Long.compare(x.getId() - y.getId(), 0L);
    }

    /**
     * Returns the {@link System#nanoTime()} comparable tick when this event occurred.
     *
     * @return the {@link System#nanoTime()} comparable tick when this event occurred.
     */
    public long getEventTick() {
        return eventTick;
    }

    /**
     * Returns the current {@link System#currentTimeMillis()} comparable time when this event occurred.
     * <strong>IMPORTANT</strong> if the system clock has changed between when the event occurred and now,
     * this function will return the time comparable to now, not the time comparable to the value of
     * {@link System#currentTimeMillis()} at the time of the event. Compare {@link #getEventTick()} with
     * {@link System#nanoTime()} if you need to measure the time since the event.
     *
     * @return the current {@link System#currentTimeMillis()} comparable time when this event occurred.
     */
    public long getEventMillis() {
        return System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(eventTick - System.nanoTime());
    }

    /**
     * Returns the {@link Queue.Item#getId()}.
     *
     * @return the {@link Queue.Item#getId()}.
     */
    public long getId() {
        return item.getId();
    }

    /**
     * Returns the state of the {@link Queue.Item} when the event occurred.
     *
     * @return the state of the {@link Queue.Item} when the event occurred.
     */
    public State getState() {
        return state;
    }

    /**
     * Returns the {@link Queue.Item}.
     *
     * @return the {@link Queue.Item}.
     */
    @NonNull
    public Queue.Item getItem() {
        return item;
    }

    /**
     * Returns the {@link Queue.Item#getAssignedLabel()} at the time of the event or {@code null} if the item was not
     * assigned to a label.
     *
     * @return the {@link Queue.Item#getAssignedLabel()} at the time of the event or {@code null} if the item was not
     * assigned to a label.
     */
    @CheckForNull
    public Label getAssignedLabel() {
        return assignedLabel;
    }

    /**
     * Returns the {@link Run} to which the {@link Queue.Item} belongs.
     *
     * @return the {@link Run} to which the {@link Queue.Item} belongs, if present.
     */
    public Optional<Run<?, ?>> getRun() {
        return Optional.ofNullable(run);
    }

    /**
     * Returns the {@link Queue.Executable} created from the {@link Queue.Item} belongs.
     *
     * @return the {@link Queue.Executable} created from the {@link Queue.Item} belongs, if present.
     */
    @NonNull
    public Optional<Queue.Executable> getExecutable() {
        return Optional.ofNullable(executable);
    }

    /**
     * Returns the {@link Node#getAssignedLabels()} of all the executor slots occupied by this task,
     * if the task has been started.
     *
     * @return the {@link Node#getAssignedLabels()} of all the executor slots occupied by this task,
     * if the task has been started, otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<List<Set<LabelAtom>>> getConsumedLabelAtoms() {
        return Optional.ofNullable(consumedLabelAtoms);
    }

    /**
     * If the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the queue.
     *
     * @return if the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the
     * queue, otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<Long> getQueuingTotalMillis() {
        return Optional.ofNullable(queuingTotalMillis);
    }

    /**
     * If the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the queue in
     * the waiting state.
     *
     * @return if the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the
     * queue in the waiting state, otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<Long> getQueuingWaitingMillis() {
        return Optional.ofNullable(queuingWaitingMillis);
    }

    /**
     * If the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the queue in
     * the blocked state.
     *
     * @return if the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the
     * queue in the blocked state, otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<Long> getQueuingBlockedMillis() {
        return Optional.ofNullable(queuingBlockedMillis);
    }

    /**
     * If the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the queue in
     * the buildable state.
     *
     * @return if the {@link Queue.Item} has left the queue, returns the number of milliseconds the item was on the
     * queue in the buildable state, otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<Long> getQueuingBuildableMillis() {
        return Optional.ofNullable(queuingBuildableMillis);
    }

    /**
     * If the {@link Queue.Executable} has finished executing, returns the number of milliseconds the item spent
     * executing.
     *
     * @return if the {@link Queue.Executable} has finished executing, returns the number of milliseconds the item
     * spent executing, otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<Long> getExecutingMillis() {
        return Optional.ofNullable(executingMillis);
    }

    /**
     * If the {@link Queue.Executable} has started executing, returns the number of executors being used.
     *
     * @return if the {@link Queue.Executable} has started executing, returns the number of executors being used,
     * otherwise {@link Optional#empty()}.
     */
    @NonNull
    public Optional<Integer> getExecutorCount() {
        return Optional.ofNullable(executorCount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        QueueItemMetricsEvent that = (QueueItemMetricsEvent) o;

        if (eventTick != that.eventTick) {
            return false;
        }
        if (!item.equals(that.item)) {
            return false;
        }
        return state == that.state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int result = (int) (eventTick ^ (eventTick >>> 32));
        result = 31 * result + item.hashCode();
        result = 31 * result + state.hashCode();
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "QueueItemMetricsEvent{" +
                "event=" + new Date(getEventMillis()) +
                ", item=" + item +
                ", state=" + state +
                ", run=" + run +
                ", executable=" + executable +
                ", queuingTotalMillis=" + queuingTotalMillis +
                ", queuingWaitingMillis=" + queuingWaitingMillis +
                ", queuingBlockedMillis=" + queuingBlockedMillis +
                ", queuingBuildableMillis=" + queuingBuildableMillis +
                ", executingMillis=" + executingMillis +
                ", executorCount=" + executorCount +
                '}';
    }

    /**
     * The state of the item when this metrics event was created.
     */
    public enum State {
        QUEUED,
        CANCELLED,
        STARTED,
        FINISHED
    }
}
