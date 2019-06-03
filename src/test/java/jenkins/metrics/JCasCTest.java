package jenkins.metrics;

import hudson.ExtensionList;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import jenkins.metrics.api.MetricsAccessKey;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests of Jenkins Configuration as Code. Not needed because we have a more strong test {@link ExportImportRoundTripJCascMetricsTest}
 */
public class JCasCTest {
    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Rule
    public RestartableJenkinsRule r = new RestartableJenkinsRule();

    @Test
    @ConfiguredWithCode("metrics-config.yml")
    public void configureAsExpected() {
        List<MetricsAccessKey> accessKeys = ExtensionList.lookup(MetricsAccessKey.DescriptorImpl.class).get(0).getAccessKeys();
        assertThat("We have an access keys of metrics configured in Configure System", accessKeys != null && accessKeys.size() == 1);

        MetricsAccessKey accessKey = accessKeys.get(0);

        assertEquals(accessKey.getKey(), "tDdG5Vsv-2-WDdHfI3QFPiU9-hcvKmWd2HL4CfVIFvUumQzz3qf6c0qt_HU4_lUh");
        assertEquals(accessKey.getDescription(), "JCasC key");
        assertEquals(accessKey.isCanPing(), false);
        assertEquals(accessKey.isCanThreadDump(), true);
        assertEquals(accessKey.isCanHealthCheck(), false);
        assertEquals(accessKey.isCanMetrics(), true);
        assertEquals(accessKey.getOrigins(), "*");
    }
}
