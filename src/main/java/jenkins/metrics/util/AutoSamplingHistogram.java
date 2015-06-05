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

package jenkins.metrics.util;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Reservoir;
import hudson.Extension;
import hudson.model.PeriodicWork;
import jenkins.metrics.api.Metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * This is a {@link Histogram} that is derived from a {@link Gauge} by sampling it 4 times a minute.
 *
 * @author Stephen Connolly
 */
public class AutoSamplingHistogram extends Histogram {

    private static final Logger LOGGER = Logger.getLogger(AutoSamplingHistogram.class.getName());
    
    private final Gauge<? extends Number> source;
    
    private volatile transient boolean badGauge;

    public AutoSamplingHistogram(Gauge<? extends Number> source) {
        this(source, new ExponentiallyDecayingReservoir());

    }

    public AutoSamplingHistogram(Gauge<? extends Number> source, Reservoir reservoir) {
        super(reservoir);
        this.source = source;
    }

    public void update() {
        try {
            Number value = source.getValue();
            if (value instanceof Integer) {
                update(value.intValue());
            } else if (value != null) {
                update(value.longValue());
            } else {
                LOGGER.log(Level.FINE, "Gauge {0} returned null", source);
            }
            badGauge = false;
        } catch (ClassCastException e) {
            LogRecord lr = new LogRecord(badGauge ? Level.FINE : Level.WARNING,
                    "Gauge {0} is supposed to return a subclass of java.lang.Number but didn't");
            badGauge = true;
            lr.setThrown(e);
            lr.setParameters(new Object[]{source});
            LOGGER.log(lr);
        }
    }

    public MetricSet toMetricSet() {
        final Map<String, Metric> metrics = new LinkedHashMap<String, Metric>(2);
        metrics.put("value", source);
        metrics.put("history", this);
        return new GaugeHistogramMetricSet(metrics);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("AutoSamplingHistogram{");
        sb.append("source=").append(source);
        sb.append('}');
        return sb.toString();
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {
        
        private final Map<Histogram,Long> lastWarning = new WeakHashMap<Histogram, Long>();
        
        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(15);
        }

        @Override
        protected synchronized void doRun() throws Exception {
            MetricRegistry registry = Metrics.metricRegistry();
            for (Histogram histogram : registry.getHistograms().values()) {
                if (histogram instanceof AutoSamplingHistogram) {
                    try {
                        ((AutoSamplingHistogram) histogram).update();
                    } catch (Exception e) {
                        Long lw = lastWarning.get(histogram);
                        final boolean warn = lw == null || lw + TimeUnit.HOURS.toMillis(1) < System.currentTimeMillis();
                        LogRecord lr = new LogRecord(warn ? Level.WARNING : Level.FINE, 
                                "Uncaught exception when calling update for {0}");
                        lr.setParameters(new Object[]{histogram});
                        lr.setThrown(e);
                        LOGGER.log(lr);
                        if (warn) {
                            lastWarning.put(histogram, System.currentTimeMillis());
                        }
                    } catch (Error e) {
                        LogRecord lr = new LogRecord(Level.WARNING, "Error encountered while attempting to update {0}");
                        lr.setParameters(new Object[]{histogram});
                        lr.setThrown(e);
                        LOGGER.log(lr);
                        throw e;
                    } catch (Throwable t) {
                        LogRecord lr = new LogRecord(Level.SEVERE, "Uncaught throwable when calling update for {0}");
                        lr.setParameters(new Object[]{histogram});
                        lr.setThrown(t);
                        LOGGER.log(lr);
                    }
                }
            }
        }

    }


    private static class GaugeHistogramMetricSet implements MetricSet {
        private final Map<String, Metric> metrics;

        public GaugeHistogramMetricSet(Map<String, Metric> metrics) {
            this.metrics = metrics;
        }

        public Map<String, Metric> getMetrics() {
            return metrics;
        }
    }
}
