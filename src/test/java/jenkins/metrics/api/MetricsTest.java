package jenkins.metrics.api;

import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.IOException;

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

        // Lambda-ify when Java 8+
        story.then(new RestartableJenkinsRule.Step() {
            @Override
            public void run(JenkinsRule j) throws Throwable {
                Assume.assumeTrue(Jenkins.getVersion().isNewerThan(new VersionNumber("2.113"))); // JENKINS-50291 introduced in 2.114

                assertTrue(Metrics.HealthChecker.getLogFile(j.jenkins).getAbsolutePath().startsWith(j.getInstance().getRootDir().getAbsolutePath()));
                System.setProperty("hudson.triggers.SafeTimerTask.logsTargetDir", folder.newFolder().getAbsolutePath());
            }
        });

        story.then(new RestartableJenkinsRule.Step() {
            @Override
            public void run(JenkinsRule j) throws Throwable {
                assertFalse(Metrics.HealthChecker.getLogFile(j.jenkins).getAbsolutePath().startsWith(j.getInstance().getRootDir().getAbsolutePath()));
            }
        });
    }
}
