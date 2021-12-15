package jenkins.metrics.api;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.recipes.LocalData;

import java.util.logging.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetricsAccessKeyTest {
    public JenkinsRule j = new JenkinsRule();
    public LoggerRule l = new LoggerRule().record("jenkins.metrics.api.MetricsAccessKey", Level.ALL).capture(1000);
    @Rule
    public RuleChain chain = RuleChain.outerRule(j).around(l);

    @Test
    @Issue("SECURITY-1624")
    @LocalData
    public void oldKeysAreConvertedAfterStartup() throws Exception {
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
    public void newKeysStayTheSame() throws Exception {
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
