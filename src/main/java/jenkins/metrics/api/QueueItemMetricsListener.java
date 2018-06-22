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

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Queue;

/**
 * Receives details of metrics events about queue items.
 */
public abstract class QueueItemMetricsListener implements ExtensionPoint {
    /**
     * Called at most once for each {@link Queue.Item} some time after it enters the queue.
     *
     * @param event the event.
     */
    public void onQueued(QueueItemMetricsEvent event) {
    }

    /**
     * Called at most once for each {@link Queue.Item} some time after it is cancelled from the queue.
     *
     * @param event the event.
     */
    public void onCancelled(QueueItemMetricsEvent event) {
    }

    /**
     * Called at most once for each {@link Queue.Item} some time after it leaves the queue and starts executing.
     *
     * @param event the event.
     */
    public void onStarted(QueueItemMetricsEvent event) {
    }

    /**
     * Called at most once for each {@link Queue.Item} some time after it finishes executing.
     *
     * @param event the event.
     */
    public void onFinished(QueueItemMetricsEvent event) {
    }

    /**
     * All the registered {@link QueueItemMetricsListener} instances.
     *
     * @return all the registered {@link QueueItemMetricsListener} instances.
     */
    public static ExtensionList<QueueItemMetricsListener> all() {
        return ExtensionList.lookup(QueueItemMetricsListener.class);
    }

    /**
     * Notify all listeners about the enqueuing of an item.
     *
     * @param event the event.
     */
    public static void notifyQueued(QueueItemMetricsEvent event) {
        all().forEach(l -> l.onQueued(event));
    }

    /**
     * Notify all listeners about the cancellation of an item.
     *
     * @param event the event.
     */
    public static void notifyCancelled(QueueItemMetricsEvent event) {
        all().forEach(l -> l.onCancelled(event));
    }

    /**
     * Notify all listeners about an item having started execution.
     *
     * @param event the event.
     */
    public static void notifyStarted(QueueItemMetricsEvent event) {
        all().forEach(l -> l.onStarted(event));
    }

    /**
     * Notify all listeners about an item having finished execution.
     *
     * @param event the event.
     */
    public static void notifyFinished(QueueItemMetricsEvent event) {
        all().forEach(l -> l.onFinished(event));
    }
}
