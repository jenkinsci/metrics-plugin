package jenkins.metrics.impl;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.PluginManager;
import hudson.PluginWrapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import jenkins.metrics.api.MetricProvider;
import jenkins.model.Jenkins;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Exposes details of various versions as metrics, which should make it easier to cross-correlate metric changes with
 * version changes.
 *
 * @since 3.1.2
 */
@Extension
public class JenkinsVersionsProviderImpl extends MetricProvider {
    /**
     * Our set of metrics.
     */
    private MetricSet set;
    private volatile long nextRefresh = Long.MIN_VALUE;

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MetricSet getMetricSet() {
        if (set == null || nextRefresh < System.currentTimeMillis()) {
            Map<String, Metric> metrics = new LinkedHashMap<String, Metric>();
            metrics.put(name("jenkins", "versions", "core"), new VersionGauge(Jenkins.VERSION));
            final Jenkins jenkins = Jenkins.getInstance();
            if (jenkins != null) {
                final PluginManager pluginManager = jenkins.getPluginManager();
                for (PluginWrapper p : pluginManager.getPlugins()) {
                    if (p.isActive()) {
                        metrics.put(name("jenkins", "versions", "plugin", p.getShortName()),
                                new VersionGauge(p.getVersion()));
                    }
                }
            }
            set = metrics(metrics); // idempotent
            nextRefresh = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15);
        }
        return set;
    }

    private static class VersionGauge implements Gauge<String> {
        private final String version;

        public VersionGauge(String v) {
            if (v == null) {
                this.version = "?";
            } else {
                int idx = v.indexOf(' ');
                this.version = idx == -1 ? v : v.substring(0, idx);
            }
        }

        public String getValue() {
            return version;
        }
    }
}
