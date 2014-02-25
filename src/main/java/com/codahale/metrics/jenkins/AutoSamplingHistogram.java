package com.codahale.metrics.jenkins;

import com.codahale.metrics.ExponentiallyDecayingReservoir;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Reservoir;
import hudson.Extension;
import hudson.model.PeriodicWork;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * This is a {@link Histogram} that is derived from a {@link Gauge} by sampling it 4 times a minute.
 *
 * @author Stephen Connolly
 */
public class AutoSamplingHistogram extends Histogram {

    private final Gauge<? extends Number> source;

    public AutoSamplingHistogram(Gauge<? extends Number> source) {
        this(source, new ExponentiallyDecayingReservoir());

    }
    public AutoSamplingHistogram(Gauge<? extends Number> source, Reservoir reservoir) {
        super(reservoir);
        this.source = source;
    }

    public void update() {
        Number value = source.getValue();
        if (value instanceof Integer) {
            update(value.intValue());
        } else {
            update(value.longValue());
        }
    }

    public MetricSet toMetricSet() {
        final Map<String,Metric> metrics = new LinkedHashMap<String, Metric>(2);
        metrics.put("value", source);
        metrics.put("history", this);
        return new GaugeHistogramMetricSet(metrics);
    }

    @Extension
    public static class PeriodicWorkImpl extends PeriodicWork {

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(15);
        }

        @Override
        protected synchronized void doRun() throws Exception {
            MetricRegistry registry = Metrics.metricRegistry();
            if (registry == null) {
                return;
            }
            for (Histogram histogram : registry.getHistograms().values()) {
                if (histogram instanceof AutoSamplingHistogram) {
                    ((AutoSamplingHistogram) histogram).update();
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
