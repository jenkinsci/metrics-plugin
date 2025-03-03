package jenkins.metrics.api;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.recipes.LocalData;

import java.util.logging.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.IsNot.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class MetricsAccessKeyTest {

    private final LogRecorder l = new LogRecorder().record("jenkins.metrics.api.MetricsAccessKey", Level.ALL).capture(1000);

    @Test
    @Issue("SECURITY-1624")
    @LocalData
    void oldKeysAreConvertedAfterStartup(JenkinsRule j) throws Exception {
        j.waitUntilNoActivityUpTo(5000);
        assertThat(l.getRecords(), hasItem(
                allOf(
                        hasProperty("level", equalTo(Level.INFO)),
                        hasProperty("message", startsWith("Saving encrypted Metrics access key"))
                )
        ));
        assertTrue(MetricsAccessKey.DescriptorImpl.isKeyConverted());
    }

    @Test
    @Issue("SECURITY-1624")
    @LocalData
    void newKeysStayTheSame(JenkinsRule j) throws Exception {
        j.waitUntilNoActivityUpTo(5000);
        assertThat(l.getRecords(), not(hasItem(
                allOf(
                        hasProperty("level", equalTo(Level.INFO)),
                        hasProperty("message", startsWith("Saving encrypted Metrics access key"))
                )
        )));
        assertFalse(MetricsAccessKey.DescriptorImpl.isKeyConverted());
    }
}
