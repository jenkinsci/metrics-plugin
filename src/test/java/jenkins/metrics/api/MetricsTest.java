package jenkins.metrics.api;

import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@WithJenkins
class MetricsTest {

    private static final String SYS_PROP_FOR_LOGS_PATH = "hudson.triggers.SafeTimerTask.logsTargetDir";
    @TempDir
    private File folder;

    @BeforeAll
    @AfterAll
    static void cleanProperty() {
        System.clearProperty(SYS_PROP_FOR_LOGS_PATH);
    }

    @Issue("JENKINS-50291")
    @Test
    void logsDirectory(JenkinsRule j) throws Throwable {
        assumeTrue(Jenkins.getVersion().isNewerThan(new VersionNumber("2.113"))); // JENKINS-50291 introduced in 2.114

        assertTrue(Metrics.HealthChecker.getLogFile(j.jenkins).getAbsolutePath().startsWith(j.getInstance().getRootDir().getAbsolutePath()));
        System.setProperty("hudson.triggers.SafeTimerTask.logsTargetDir", newFolder(folder, "junit").getAbsolutePath());

        j.restart();

        assertFalse(Metrics.HealthChecker.getLogFile(j.jenkins).getAbsolutePath().startsWith(j.getInstance().getRootDir().getAbsolutePath()));
    }

    @Test
    void jmxDomain(JenkinsRule j) {
        assertTrue(ArrayUtils.contains(ManagementFactory.getPlatformMBeanServer().getDomains(), "io.jenkins"));
    }

    @Test
    void jmxMetricsExcluded(JenkinsRule j) throws Exception {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Gc,*"), null), is(empty()));
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Vm,*"), null), is(empty()));
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:*,name=History"), null), is(empty()));
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:*,name=5m"), null), is(empty()));
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:*,name=1h"), null), is(empty()));
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Project,*"), null), is(not(empty())));
        assertThat(mBeanServer.queryNames(new ObjectName("io.jenkins:type=Executor,*"), null), is(not(empty())));
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
