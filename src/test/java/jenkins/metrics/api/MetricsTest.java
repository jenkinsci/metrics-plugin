package jenkins.metrics.api;

import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.apache.commons.lang.ArrayUtils;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetricsTest {

    private static final String SYSPROP_FOR_LOGS_PATH = "hudson.triggers.SafeTimerTask.logsTargetDir";
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();
    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @BeforeClass
    @AfterClass
    public static void cleanProperty() throws IOException {
        System.clearProperty(SYSPROP_FOR_LOGS_PATH);
    }


    @Issue("JENKINS-50291")
    @Test
    public void logsDirectory() {

        story.then(j -> {
            Assume.assumeTrue(Jenkins.getVersion().isNewerThan(new VersionNumber("2.113"))); // JENKINS-50291 introduced in 2.114

            assertTrue(Metrics.HealthChecker.getLogFile(j.jenkins).getAbsolutePath().startsWith(j.getInstance().getRootDir().getAbsolutePath()));
            System.setProperty("hudson.triggers.SafeTimerTask.logsTargetDir", folder.newFolder().getAbsolutePath());
        });

        story.then(j -> assertFalse(Metrics.HealthChecker.getLogFile(j.jenkins).getAbsolutePath().startsWith(j.getInstance().getRootDir().getAbsolutePath())));
    }

    @Test
    public void jmxDomain() {
        story.then(r -> {
            assertTrue(ArrayUtils.contains(ManagementFactory.getPlatformMBeanServer().getDomains(), "io.jenkins"));
        });
    }
}
