package jenkins.metrics;

import hudson.ExtensionList;
import hudson.util.Secret;
import io.jenkins.plugins.casc.misc.junit.jupiter.AbstractRoundTripTest;
import jenkins.metrics.api.MetricsAccessKey;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration, validation, export and configuration during restart of the configuration of the metrics plugin.
 */
@WithJenkins
class RoundTripJCascMetricsTest extends AbstractRoundTripTest {

    @Override
    protected void assertConfiguredAsExpected(JenkinsRule j, String configContent) {
        List<MetricsAccessKey> accessKeys = ExtensionList.lookup(MetricsAccessKey.DescriptorImpl.class).get(0).getAccessKeys();
        assertThat("We have an access key of metrics configured in Configure System", accessKeys.size() == 4);

        MetricsAccessKey accessKey = accessKeys.get(0);

        assertEquals("tDdG5Vsv-2-WDdHfI3QFPiU9-hcvKmWd2HL4CfVIFvUumQzz3qf6c0qt_HU4_lUh", Secret.toString(accessKey.getKey()));
        assertEquals("JCasC key", accessKey.getDescription());
        assertFalse(accessKey.isCanPing());
        assertTrue(accessKey.isCanThreadDump());
        assertFalse(accessKey.isCanHealthCheck());
        assertTrue(accessKey.isCanMetrics());
        assertEquals("*", accessKey.getOrigins());
    }

    @Override
    protected String stringInLogExpected() {
        return "MetricsAccessKey.key = ****";
    }
}
