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

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;
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

    @Test
    public void jmxMetricsExcluded() {
        story.then(r -> {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Gc,*"), null), is(empty()));
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Vm,*"), null), is(empty()));
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:*,name=History"), null), is(empty()));
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:*,name=5m"), null), is(empty()));
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:*,name=1h"), null), is(empty()));
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Project,*"), null), is(not(empty())));
            assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Executor,*"), null), is(not(empty())));
        });
    }
}
