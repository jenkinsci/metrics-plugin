package com.codahale.metrics.jenkins;

import com.codahale.metrics.health.HealthCheck;
import hudson.ExtensionPoint;

import java.util.Map;

/**
 * @author Stephen Connolly
 */
public abstract class HealthCheckProvider implements ExtensionPoint {

    public abstract Map<String, HealthCheck> getHealthChecks();
}
