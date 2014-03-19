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

import com.codahale.metrics.health.HealthCheck;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionPoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides {@link HealthCheck} instances to register.
 */
public abstract class HealthCheckProvider implements ExtensionPoint {

    protected static Map.Entry<String, HealthCheck> check(String name, HealthCheck metric) {
        return new StringImmutableEntry<HealthCheck>(name, metric);
    }

    protected static Map.Entry<String, HealthCheck> check(String name, HealthCheck metric, boolean enabled) {
        return new StringImmutableEntry<HealthCheck>(name, enabled ? metric : null);
    }

    protected static Map<String, HealthCheck> checks(Map.Entry<String, HealthCheck>... metrics) {
        final Map<String, HealthCheck> result = new LinkedHashMap<String, HealthCheck>(metrics.length);
        for (Map.Entry<String, HealthCheck> metric : metrics) {
            if (metric.getValue() != null) {
                result.put(metric.getKey(), metric.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * A map of {@link HealthCheck} instances keyed by name.
     *
     * @return a map of {@link HealthCheck} instances keyed by name.
     */
    @NonNull
    public abstract Map<String, HealthCheck> getHealthChecks();


}
