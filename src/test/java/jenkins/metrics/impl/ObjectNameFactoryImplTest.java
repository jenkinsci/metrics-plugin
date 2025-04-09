package jenkins.metrics.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectNameFactoryImplTest {
    private static final String JMX_DOMAIN = "io.jenkins";

    private ObjectNameFactoryImpl instance;

    @BeforeEach
    void setUp() {
        instance = new ObjectNameFactoryImpl();
    }

    @Test
    void createName() throws MalformedObjectNameException {
        assertObjectName("jenkins.myname", "type=Myname");
        assertObjectName("jenkins.plugins.enabled", "type=Plugins,name=Enabled");
        assertObjectName("jenkins.plugins.disabled.value", "type=Plugins,name=Disabled");
        assertObjectName("jenkins.project.disabled.count.value", "type=Project,name=Disabled");
        assertObjectName("jenkins.job.blocked.duration", "type=Job,name=Blocked");
        assertObjectName("http.responseCodes.ok", "type=Http,class=ResponseCodes,name=Ok");
        assertObjectName("a.b.c.d.e.f", "type=A,class=B,name=C.d.e.f");
    }

    private void assertObjectName(String name, String objectName) throws MalformedObjectNameException {
        assertEquals(new ObjectName(JMX_DOMAIN + ":" + objectName), instance.createName("meters", JMX_DOMAIN, name));
    }
}
