/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
package jenkins.metrics.util;

import java.io.IOException;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import com.codahale.metrics.health.HealthCheckRegistry;

import hudson.util.DaemonThreadFactory;
import hudson.util.ExceptionCatchingThreadFactory;
import jenkins.metrics.api.Metrics.HealthChecker;

/**
 * Thread pool for running health checks. We set the pool size to 4 by default (configurable with system property
 * jenkins.metrics.util.HealthChecksThreadPool.maxThreadNumber) and we keep threads around for 5 seconds as this is a
 * bursty pool used once per minute.
 * 
 * The queue size is limited to the current number of health checks dynamically, minus the 4 threads in the pool, plus
 * one, as the {@link HealthChecker} itself is executed in the pool too. For example for 10 health checks we have the
 * thread pool (4) + the queue (7) = 11 for the 10 health checks and the HealthChecker.
 * 
 * The {@link RejectedExecutionHandler} is configured to drop oldest items in the queue as new ones come in, to avoid
 * running more than one health check in each recurrence period.
 * 
 * @since 3.1.2.3
 */
public class HealthChecksThreadPool extends ThreadPoolExecutor {

    private static final Logger LOGGER = Logger.getLogger(HealthChecksThreadPool.class.getName());

    private static final int MAX_THREAD_POOL_SIZE = Integer
            .parseInt(System.getProperty(HealthChecksThreadPool.class.getName() + ".maxThreadNumber", "4"));

    private static AtomicLong rejectedExecutions = new AtomicLong(0);

    private HealthCheckRegistry healthCheckRegistry;

    public HealthChecksThreadPool(HealthCheckRegistry healthCheckRegistry) {
        super(MAX_THREAD_POOL_SIZE, MAX_THREAD_POOL_SIZE, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), //
                new ExceptionCatchingThreadFactory(new DaemonThreadFactory(new ThreadFactory() {
                    private final AtomicInteger number = new AtomicInteger();

                    public Thread newThread(Runnable r) {
                        return new Thread(r, "Metrics-HealthChecks-" + number.incrementAndGet());
                    }
                })), new MetricsRejectedExecutionHandler(healthCheckRegistry));
        this.allowCoreThreadTimeOut(true); // allow stopping all threads if idle
        this.healthCheckRegistry = healthCheckRegistry;
        LOGGER.log(Level.FINE,
                "Created thread pool with a max of {0} threads (plus {1} in queue) for {2} health checks",
                new Object[] { getMaximumPoolSize(),
                               Math.max(0, healthCheckRegistry.getNames().size() + 1 - getMaximumPoolSize()),
                               healthCheckRegistry.getNames().size() });
    }

    /**
     * Manually handle the queue size so it doesn't grow over our calculated queue capacity based on the number of
     * health checks
     */
    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        int size = getQueue().size();
        int running = getActiveCount();
        SortedSet<String> names = healthCheckRegistry.getNames();
        int limit = names.size();
        LOGGER.log(Level.FINER,
                "Executing health check, active={0} queued={1} thread pool={2} (max {3}) for {4} health checks: {5}",
                new Object[] {running, size, getPoolSize(), getMaximumPoolSize(), limit, names});
        // avoid going over queueCapacity, drop the oldest in queue if that happens
        // if there is any race condition MetricsRejectedExecutionHandler will catch it anyway
        if (running + size > limit + 1) {
            dropOldestInQueue(this, healthCheckRegistry);
        }
        super.beforeExecute(t, r);
    }

    /**
     * Drop the oldest health check in executor queue and cancel it
     */
    static void dropOldestInQueue(ThreadPoolExecutor executor, HealthCheckRegistry healthCheckRegistry) {
        // capture the state up front
        Object[] params = {
                executor, executor.getQueue().size(), healthCheckRegistry.getNames(),
                healthCheckRegistry.getNames().size(), Arrays.asList(executor.getQueue().toArray())
        };

        Runnable discarded = executor.getQueue().poll();
        // if there are two workers taking jobs concurrently, the queue will be empty by the time we get here
        if (discarded != null) {
            LOGGER.log(Level.WARNING,
                    "Too many health check executions queued, dropping oldest one. This may mean some health checks "
                            + "are taking too long to execute:"
                            + " {0}, queue size={1}, health checks={2} ({3}) {4}",
                    params);
            cancelQueuedHealthCheck(discarded);
        }
    }

    /**
     * Cancel the future execution, so that
     * {@link HealthCheckRegistry#runHealthChecks(java.util.concurrent.ExecutorService)} doesn't wait indefinitely. It
     * is not enough with removing it from the queue.
     */
    @SuppressWarnings("rawtypes")
    private static void cancelQueuedHealthCheck(Runnable discarded) {
        if (discarded instanceof Future) {
            // it has to be a Future
            ((Future) discarded).cancel(false);
        } else {
            LOGGER.log(Level.WARNING, "HealthCheck Runnable is not an instance of Future: {0}", discarded);
        }
    }

    @Restricted(DoNotUse.class) // testing only
    public static long getRejectedExecutions() {
        return rejectedExecutions.get();
    }

    /**
     * Log the rejection, discard the first (oldest) item in the queue and retry. Should only happen when beforeExecute
     * is called simultaneously and doesn't preemptively avoid going over the calculated max size for the queue.
     */
    private static class MetricsRejectedExecutionHandler implements RejectedExecutionHandler {

        private HealthCheckRegistry healthCheckRegistry;

        public MetricsRejectedExecutionHandler(HealthCheckRegistry healthCheckRegistry) {
            this.healthCheckRegistry = healthCheckRegistry;
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectedExecutions.incrementAndGet();
            // copied from DiscardOldestPolicy to ensure health check gets cancelled
            if (!executor.isShutdown()) {
                LOGGER.log(Level.WARNING,
                        "Execution of health check was rejected:" + " {0}, queue size={1}, health checks={2} ({3})",
                        new Object[]{
                                executor, executor.getQueue().size(), healthCheckRegistry.getNames(),
                                healthCheckRegistry.getNames().size()
                        });
                dropOldestInQueue(executor, healthCheckRegistry);
                executor.execute(r);
            }
        }
    }

}
