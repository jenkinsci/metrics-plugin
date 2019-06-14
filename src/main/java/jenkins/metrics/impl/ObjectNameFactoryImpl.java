package jenkins.metrics.impl;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import com.codahale.metrics.jmx.ObjectNameFactory;
import org.apache.commons.lang.StringUtils;

public class ObjectNameFactoryImpl implements ObjectNameFactory {
    private static final Logger LOGGER = Logger.getLogger(ObjectNameFactoryImpl.class.getName());

    private static final String[] jmxOrg = {"type", "class", "name"};

    @Override
    public ObjectName createName(String type, String domain, String name) {
        String tmpName = StringUtils.removeStart(name, "jenkins.");
        tmpName = StringUtils.removeEnd(tmpName, ".value");
        tmpName = StringUtils.removeEnd(tmpName, ".duration");
        tmpName = StringUtils.removeEnd(tmpName, ".count");
        String[] split = tmpName.split("\\.",3);
        StringBuilder sb = new StringBuilder().append(domain).append(":");
        for (int i = 0; i < split.length; i++) {
            int orgIndex = i;
            if (i > 0) {
                sb.append(",");
                if (i == split.length - 1) {
                    orgIndex = jmxOrg.length - 1;
                }
            }
            sb.append(jmxOrg[orgIndex]).append("=").append(StringUtils.capitalize(split[i]));
        }
        String jmxName = sb.toString();
        try {
            ObjectName objectName = new ObjectName(jmxName);
            if (objectName.isPattern()) {
                objectName = new ObjectName(ObjectName.quote(jmxName));
            }
            return objectName;
        } catch (MalformedObjectNameException e) {
            try {
                return new ObjectName(domain, "name", ObjectName.quote(tmpName));
            } catch (MalformedObjectNameException e1) {
                String finalName = tmpName;
                LOGGER.log(Level.WARNING, e1, () -> "Unable to register " + type + " " + finalName);
                throw new RuntimeException(e1);
            }
        }
    }
}
