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

package jenkins.metrics.api;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides metrics to register.
 */
public abstract class MetricProvider implements ExtensionPoint {
    protected static Map.Entry<String, Metric> metric(String name, Metric metric) {
        return metric == null ? null : new StringImmutableEntry<Metric>(name, metric);
    }

    protected static MetricSet metrics(Map.Entry<String, Metric>... metrics) {
        final Map<String, Metric> result = new LinkedHashMap<String, Metric>(metrics.length);
        for (Map.Entry<String, Metric> metric : metrics) {
            if (metric != null && metric.getValue() != null) {
                result.put(metric.getKey(), metric.getValue());
            }
        }
        return new FixedMetricSet(result);
    }

    protected static MetricSet metrics(Map<String, Metric> metrics) {
        final Map<String, Metric> result = new LinkedHashMap<String, Metric>(metrics.size());
        for (Map.Entry<String, Metric> metric : metrics.entrySet()) {
            if (metric != null && metric.getValue() != null) {
                result.put(metric.getKey(), metric.getValue());
            }
        }
        return new FixedMetricSet(result);
    }

    /**
     * Returns the set of metrics to register.
     *
     * @return the set of metrics to register.
     */
    @NonNull
    public abstract MetricSet getMetricSet();

    private static class FixedMetricSet implements MetricSet {
        private final Map<String, Metric> result;

        public FixedMetricSet(Map<String, Metric> result) {
            this.result = Collections.unmodifiableMap(result);
        }

        public Map<String, Metric> getMetrics() {
            return result;
        }
    }
}
