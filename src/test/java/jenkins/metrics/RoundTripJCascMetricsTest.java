package jenkins.metrics;

import hudson.ExtensionList;
import io.jenkins.plugins.casc.misc.RoundTripAbstractTest;
import jenkins.metrics.api.MetricsAccessKey;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Configuration, validation, export and configuration during restart of the configuration of the metrics plugin.
 */
public class RoundTripJCascMetricsTest extends RoundTripAbstractTest {

    @Override
    public void assertConfiguredAsExpected(RestartableJenkinsRule j, String configContent) {
        List<MetricsAccessKey> accessKeys = ExtensionList.lookup(MetricsAccessKey.DescriptorImpl.class).get(0).getAccessKeys();
        assertThat("We have an access key of metrics configured in Configure System", accessKeys.size() == 4);

        MetricsAccessKey accessKey = accessKeys.get(0);

        assertEquals("tDdG5Vsv-2-WDdHfI3QFPiU9-hcvKmWd2HL4CfVIFvUumQzz3qf6c0qt_HU4_lUh", accessKey.getKey());
        assertEquals("JCasC key", accessKey.getDescription());
        assertFalse(accessKey.isCanPing());
        assertTrue(accessKey.isCanThreadDump());
        assertFalse(accessKey.isCanHealthCheck());
        assertTrue(accessKey.isCanMetrics());
        assertEquals("*", accessKey.getOrigins());
    }

    @Override
    public String stringInLogExpected() {
        return "MetricsAccessKey.key = tDdG5Vsv-2-WDdHfI3QFPiU9-hcvKmWd2HL4CfVIFvUumQzz3qf6c0qt_HU4_lUh";
    }
}
