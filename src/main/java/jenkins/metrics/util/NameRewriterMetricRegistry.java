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

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricRegistryListener;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import org.apache.commons.lang.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A pseudo {@link MetricRegistry} that adds a prefix / postfix to every metric name.
 */
public class NameRewriterMetricRegistry extends MetricRegistry {
    private final MetricRegistry delegate;
    private final String prefix;
    private final String postfix;
    private final boolean wrap;

    public NameRewriterMetricRegistry(String prefix, MetricRegistry delegate, String postfix) {
        this(prefix, delegate, postfix, false);
    }

    public NameRewriterMetricRegistry(String prefix, MetricRegistry delegate, String postfix, boolean wrap) {
        this.prefix = prefix;
        this.delegate = delegate;
        this.postfix = postfix;
        this.wrap = wrap;
    }

    private <T> SortedMap<String, T> rewrite(SortedMap<String, T> original) {
        if (StringUtils.isBlank(prefix) && StringUtils.isBlank(postfix)) {
            return original;
        }
        SortedMap<String, T> result = new TreeMap<String, T>(original.comparator());
        for (Map.Entry<String, T> entry : original.entrySet()) {
            result.put(name(prefix, entry.getKey(), postfix), entry.getValue());
        }
        return result;
    }

    private <T> Map<String, T> rewrite(Map<String, T> original) {
        if (StringUtils.isBlank(prefix) && StringUtils.isBlank(postfix)) {
            return original;
        }
        Map<String, T> result = new LinkedHashMap<String, T>(original.size());
        for (Map.Entry<String, T> entry : original.entrySet()) {
            result.put(name(prefix, entry.getKey(), postfix), entry.getValue());
        }
        return result;
    }

    private <T> SortedSet<String> rewrite(SortedSet<String> original) {
        if (StringUtils.isBlank(prefix) && StringUtils.isBlank(postfix)) {
            return original;
        }
        SortedSet<String> result = new TreeSet<String>(original.comparator());
        for (String name : original) {
            result.add(name(prefix, name, postfix));
        }
        return result;
    }

    private MetricFilter wrap(MetricFilter filter) {
        if (!wrap || (StringUtils.isBlank(prefix) && StringUtils.isBlank(postfix))) {
            return filter;
        }
        return new WrappedMetricFilter(filter);
    }

    @Override
    public SortedMap<String, Counter> getCounters() {
        return rewrite(delegate.getCounters());
    }

    @Override
    public SortedMap<String, Counter> getCounters(MetricFilter filter) {
        return rewrite(delegate.getCounters(wrap(filter)));
    }

    @Override
    public SortedMap<String, Gauge> getGauges() {
        return rewrite(delegate.getGauges());
    }

    @Override
    public SortedMap<String, Gauge> getGauges(MetricFilter filter) {
        return rewrite(delegate.getGauges(wrap(filter)));
    }

    @Override
    public SortedMap<String, Histogram> getHistograms() {
        return rewrite(delegate.getHistograms());
    }

    @Override
    public SortedMap<String, Histogram> getHistograms(MetricFilter filter) {
        return rewrite(delegate.getHistograms(wrap(filter)));
    }

    @Override
    public SortedMap<String, Meter> getMeters() {
        return rewrite(delegate.getMeters());
    }

    @Override
    public SortedMap<String, Meter> getMeters(MetricFilter filter) {
        return rewrite(delegate.getMeters(wrap(filter)));
    }

    @Override
    public Map<String, Metric> getMetrics() {
        return rewrite(delegate.getMetrics());
    }

    @Override
    public SortedSet<String> getNames() {
        return rewrite(delegate.getNames());
    }

    @Override
    public SortedMap<String, Timer> getTimers() {
        return rewrite(delegate.getTimers());
    }

    @Override
    public SortedMap<String, Timer> getTimers(MetricFilter filter) {
        return rewrite(delegate.getTimers(wrap(filter)));
    }

    @Override
    public Timer timer(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeMatching(MetricFilter filter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeListener(MetricRegistryListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerAll(MetricSet metrics) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T extends Metric> T register(String name, T metric) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Meter meter(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Histogram histogram(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Counter counter(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addListener(MetricRegistryListener listener) {
        throw new UnsupportedOperationException();
    }

    private final class WrappedMetricFilter implements MetricFilter {
        private final MetricFilter filter;

        public WrappedMetricFilter(MetricFilter filter) {
            this.filter = filter;
        }

        public boolean matches(String name, Metric metric) {
            return filter.matches(name(prefix, name, postfix), metric);
        }
    }
}
