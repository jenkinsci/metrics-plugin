package com.codahale.metrics.jenkins;

import com.codahale.metrics.MetricSet;
import hudson.Extension;
import hudson.ExtensionPoint;

/**
 * @author Stephen Connolly
 */
public abstract class MetricProvider implements ExtensionPoint {
    public abstract MetricSet getMetricSet();
}
