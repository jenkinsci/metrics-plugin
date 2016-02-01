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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * HEALTH_CHECKER_MAX_THREAD_POOL_SIZE) and we keep threads around for 5 seconds as this is a bursty pool used once per
 * minute.
 * 
 * The queue is set to the same size as the number of health checks, minus the 4 threads in the pool, plus one, as the
 * {@link HealthChecker} itself is executed in the pool too. For example for 10 health checks we have the thread pool
 * (4) + the queue (7) = 11 for the 10 health checks and the HealthChecker.
 * 
 * The {@link RejectedExecutionHandler} is configured to drop oldest items in the queue as new ones come in, to avoid
 * running more than one health check in each recurrence period.
 * 
 * @since 3.1.2.3
 */
public class HealthChecksThreadPool extends ThreadPoolExecutor {

    private static final Logger LOGGER = Logger.getLogger(HealthChecksThreadPool.class.getName());

    private static final int MAX_THREAD_POOL_SIZE = Integer
            .parseInt(System.getProperty("HEALTH_CHECKER_MAX_THREAD_POOL_SIZE", "4"));

    private static long rejectedExecutions;

    public HealthChecksThreadPool(HealthCheckRegistry healthCheckRegistry) {
        super(0, MAX_THREAD_POOL_SIZE, //
                5L, TimeUnit.SECONDS, //
                // 1 thread is taken by the executor itself
                new ArrayBlockingQueue<Runnable>(
                        Math.max(0, 1 + healthCheckRegistry.getNames().size() - MAX_THREAD_POOL_SIZE)), //
                new ExceptionCatchingThreadFactory(new DaemonThreadFactory(new ThreadFactory() {
                    private final AtomicInteger number = new AtomicInteger();

                    public Thread newThread(Runnable r) {
                        return new Thread(r, "Metrics-HealthChecks-" + number.incrementAndGet());
                    }
                })), new MetricsRejectedExecutionHandler(healthCheckRegistry));
    }

    @Restricted(DoNotUse.class) // testing only
    public static long getRejectedExecutions() {
        return rejectedExecutions;
    }

    /**
     * Log the rejection and execute the {@link DiscardOldestPolicy} handler, dropping the first item in the queue and
     * retrying
     */
    private static class MetricsRejectedExecutionHandler extends DiscardOldestPolicy
            implements RejectedExecutionHandler {

        private HealthCheckRegistry healthCheckRegistry;

        public MetricsRejectedExecutionHandler(HealthCheckRegistry healthCheckRegistry) {
            this.healthCheckRegistry = healthCheckRegistry;
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            rejectedExecutions++;
            LOGGER.log(Level.WARNING,
                    "Execution of health check was rejected, will drop oldest in queue, this may mean some health checks are taking too long to execute:"
                            + " {0}, queue max size={1}, health checks={2} ({3})",
                    new Object[] { executor, executor.getQueue().size(), healthCheckRegistry.getNames(),
                            healthCheckRegistry.getNames().size() });
            super.rejectedExecution(r, executor);
        }
    }

}
