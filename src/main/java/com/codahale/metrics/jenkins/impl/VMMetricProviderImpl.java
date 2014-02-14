package com.codahale.metrics.jenkins.impl;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jenkins.MetricProvider;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import hudson.Extension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Stephen Connolly
 */
@Extension
public class VMMetricProviderImpl extends MetricProvider {
    private final Map<String,Metric> map;
    private final MetricSet set = new MetricSet() {
        public Map<String, Metric> getMetrics() {
            return map;
        }
    };

    public VMMetricProviderImpl() {
        map = new LinkedHashMap<String, Metric>();
        map.put(MetricRegistry.name("vm", "memory"), new MemoryUsageGaugeSet());
        map.put(MetricRegistry.name("vm", "gc"), new GarbageCollectorMetricSet());
        map.put(MetricRegistry.name("vm", "file.descriptor.ratio"), new FileDescriptorRatioGauge());
        map.put(MetricRegistry.name("vm"), new ThreadStatesGaugeSet());
    }

    @Override
    public MetricSet getMetricSet() {
        return set;
    }
}
