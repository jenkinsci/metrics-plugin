package jenkins.metrics.api;

import hudson.ExtensionList;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import java.util.List;
import jenkins.metrics.api.MetricsAccessKey.DescriptorImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertNotNull;

public class ConfigAsCodeTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void should_support_jcasc_from_yaml() throws Exception {
        ConfigurationAsCode
            .get().configure(ConfigAsCodeTest.class.getResource("configuration-as-code.yml").toString());
        final ExtensionList<DescriptorImpl> metricsDescriptors = ExtensionList.lookup(MetricsAccessKey.DescriptorImpl.class);
        assertNotNull(metricsDescriptors);
        assertThat(metricsDescriptors, hasSize(1));

        MetricsAccessKey.DescriptorImpl metricsDescriptor = metricsDescriptors.get(0);

        final List<MetricsAccessKey> accessKeys = metricsDescriptor.getAccessKeys();
        assertThat(accessKeys, hasSize(1));

        MetricsAccessKey accessKey = accessKeys.get(0);
        assertThat(accessKey.getKey(), is("evergreen"));
        assertThat(accessKey.getDescription(), is("Key for evergreen health-check"));
        assertThat(accessKey.isCanHealthCheck(), is(true));
        assertThat(accessKey.isCanPing(), is(false));
        assertThat(accessKey.isCanThreadDump(), is(false));
        assertThat(accessKey.isCanMetrics(), is(false));
        assertThat(accessKey.getOrigins(), is("*"));
    }

    @Test
    public void should_support_jcasc_to_yaml() throws Exception {
        ConfigurationAsCode
            .get().configure(ConfigAsCodeTest.class.getResource("configuration-as-code.yml").toString());
        final ExtensionList<DescriptorImpl> metricsDescriptors = ExtensionList.lookup(MetricsAccessKey.DescriptorImpl.class);
        assertNotNull(metricsDescriptors);
        assertThat(metricsDescriptors, hasSize(1));

        List<MetricsAccessKey> accessKeys = metricsDescriptors.get(0).getAccessKeys();
        assertNotNull(accessKeys);
        assertThat(accessKeys, hasSize(1));

        MetricsAccessKey metricsAccessKey= accessKeys.get(0);
        assertNotNull(metricsAccessKey);

        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);
        final Configurator c = context.lookupOrFail(MetricsAccessKey.class);
        final CNode node = c.describe(metricsAccessKey, context);
        assertNotNull(node);
        final Mapping accessKey = node.asMapping();

        assertThat(accessKey.getScalarValue("key"), is("evergreen"));
        assertThat(accessKey.getScalarValue("description"), is("Key for evergreen health-check"));
        assertThat(accessKey.getScalarValue("canHealthCheck"), is("true"));
        assertThat(accessKey.getScalarValue("canPing"), is("false"));
        assertThat(accessKey.getScalarValue("canThreadDump"), is("false"));
        assertThat(accessKey.getScalarValue("canMetrics"), is("false"));
        assertThat(accessKey.getScalarValue("origins"), is("*"));
    }

}
