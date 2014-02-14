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

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jenkins.MetricProvider;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Stephen Connolly
 */
@Extension
public class VMMetricProviderImpl extends MetricProvider {
    private final Map<String, Metric> map;
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

    @NonNull
    @Override
    public MetricSet getMetricSet() {
        return set;
    }
}
