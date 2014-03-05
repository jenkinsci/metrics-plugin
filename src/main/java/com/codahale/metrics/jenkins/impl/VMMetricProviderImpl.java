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

package com.codahale.metrics.jenkins.impl;

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jenkins.MetricProvider;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * @author Stephen Connolly
 */
@Extension
public class VMMetricProviderImpl extends MetricProvider {
    private final MetricSet set;
    private final Gauge<Double> systemCpuLoad;
    private final Gauge<Double> vmCpuLoad;

    public VMMetricProviderImpl() {
        Gauge<Double> systemLoad = new
                Gauge<Double>() {
                    public Double getValue() {
                        OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
                        return operatingSystemMXBean.getSystemLoadAverage();
                    }
                };
        Gauge<Double> cpuUsage;
        try {
            cpuUsage = new CpuUsageGauge(1, TimeUnit.SECONDS);
        } catch (UnsupportedOperationException e) {
            // not supported
            cpuUsage = null;
        }
        systemCpuLoad = systemLoad.getValue() >= 0 ? systemLoad : null;
        vmCpuLoad = cpuUsage;
        set = metrics(
                metric(MetricRegistry.name("vm", "memory"), new MemoryUsageGaugeSet()),
                metric(MetricRegistry.name("vm", "gc"), new GarbageCollectorMetricSet()),
                metric(MetricRegistry.name("vm", "file.descriptor.ratio"), new FileDescriptorRatioGauge()),
                metric(MetricRegistry.name("vm"), new ThreadStatesGaugeSet()),
                metric(MetricRegistry.name("vm", "uptime", "milliseconds"), new Gauge<Long>() {
                    public Long getValue() {
                        return ManagementFactory.getRuntimeMXBean().getUptime();
                    }
                }),
                metric(MetricRegistry.name("system", "cpu", "load"), systemCpuLoad),
                metric(MetricRegistry.name("vm", "cpu", "load"), vmCpuLoad)
        );
    }

    /**
     * Returns a gauge that reports the current system CPU load or {@code null} if that metric is unavailable.
     *
     * @return a gauge that reports the current system CPU load or {@code null} if that metric is unavailable.
     */
    @CheckForNull
    public Gauge<Double> getSystemCpuLoad() {
        return systemCpuLoad;
    }

    /**
     * Returns a gauge that reports the current JVM CPU load or {@code null} if that metric is unavailable.
     *
     * @return a gauge that reports the current JVM CPU load or {@code null} if that metric is unavailable.
     */
    @CheckForNull
    public Gauge<Double> getVmCpuLoad() {
        return vmCpuLoad;
    }

    @NonNull
    @Override
    public MetricSet getMetricSet() {
        return set;
    }

    private static class CpuUsageGauge extends CachedGauge<Double> {
        private final RuntimeMXBean runtimeMXBean;
        private final Method getProcessCpuTime;
        private final OperatingSystemMXBean operatingSystemMXBean;
        long prevUptime;
        long prevCpu;

        public CpuUsageGauge(int timeout, TimeUnit timeUnit) {
            super(timeout, timeUnit);
            runtimeMXBean = ManagementFactory.getRuntimeMXBean();
            operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
            try {
                getProcessCpuTime = operatingSystemMXBean.getClass().getMethod("getProcessCpuTime");
                getProcessCpuTime.setAccessible(true);
                if (((Number) getProcessCpuTime.invoke(operatingSystemMXBean)).longValue() < 0) {
                    throw new UnsupportedOperationException("CPU usage not supported");
                }
            } catch (ClassCastException e) {
                throw new UnsupportedOperationException("CPU usage not supported", e);
            } catch (InvocationTargetException e) {
                throw new UnsupportedOperationException("CPU usage not supported", e);
            } catch (IllegalAccessException e) {
                throw new UnsupportedOperationException("CPU usage not supported", e);
            } catch (NoSuchMethodException e) {
                throw new UnsupportedOperationException("CPU usage not supported", e);
            }
        }

        @Override
        protected synchronized Double loadValue() {
            try {
                long uptime = runtimeMXBean.getUptime();
                long cpu = ((Number) getProcessCpuTime.invoke(operatingSystemMXBean)).longValue();
                long elapsedTime = uptime - prevUptime;
                double elapsedCpu = TimeUnit.NANOSECONDS.toMillis(cpu - prevCpu);
                prevUptime = uptime;
                prevCpu = cpu;
                return Math.min(99.0, elapsedCpu / elapsedTime);
            } catch (IllegalAccessException e) {
                // should never happen as pre-flight test caught it
                return -1.0;
            } catch (InvocationTargetException e) {
                // should never happen as pre-flight test caught it
                return -1.0;
            }
        }
    }
}
