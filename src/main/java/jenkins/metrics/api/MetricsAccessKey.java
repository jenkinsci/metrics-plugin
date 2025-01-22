/*
 * The MIT License
 *
 * Copyright (c) 2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.metrics.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.HttpResponses;
import hudson.util.Secret;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.acegisecurity.AccessDeniedException;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.Locator2;
import org.xml.sax.helpers.DefaultHandler;

import net.jcip.annotations.GuardedBy;
import jakarta.servlet.ServletException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Stephen Connolly
 */
public class MetricsAccessKey extends AbstractDescribableImpl<MetricsAccessKey> implements Serializable {
    private static final long serialVersionUID = 1L;
    @Deprecated
    private transient String key;
    @NonNull
    private Secret secretKey;
    @CheckForNull
    private final String description;
    private final boolean canPing;
    private final boolean canThreadDump;
    private final boolean canHealthCheck;
    private final boolean canMetrics;
    private final String origins;
    /**
     * Cache of regular expressions being produced for {@link #origins}.
     * It will be recalculated on the first access.
     */
    private transient String[] originRegexs = null;

    public MetricsAccessKey(String description, String key) {
        this(description, Secret.fromString(key), true, false, false, true, null);
    }

    @Deprecated
    public MetricsAccessKey(String description, String key, boolean canPing, boolean canThreadDump,
                            boolean canHealthCheck, boolean canMetrics, String origins) {
        this(description, Secret.fromString(key), canPing, canThreadDump, canHealthCheck, canMetrics, origins);
    }

    @DataBoundConstructor
    public MetricsAccessKey(String description, Secret key, boolean canPing, boolean canThreadDump,
                            boolean canHealthCheck, boolean canMetrics, String origins) {
        this.description = Util.fixEmptyAndTrim(description);
        this.secretKey = key;
        this.canPing = canPing;
        this.canThreadDump = canThreadDump;
        this.canHealthCheck = canHealthCheck;
        this.canMetrics = canMetrics;
        this.origins = origins;
    }

    private static String globToRegex(String line) {
        StringBuilder buf = new StringBuilder(line.length() + 16);
        boolean escaping = false;
        int braceDepth = 0;
        for (char c : line.toCharArray()) {
            switch (c) {
                case '*':
                    if (escaping) {
                        buf.append("\\*");
                    } else {
                        buf.append(".*");
                    }
                    escaping = false;
                    break;
                case '?':
                    if (escaping) {
                        buf.append("\\?");
                    } else {
                        buf.append('.');
                    }
                    escaping = false;
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    buf.append('\\');
                    buf.append(c);
                    escaping = false;
                    break;
                case '\\':
                    if (escaping) {
                        buf.append("\\\\");
                        escaping = false;
                    } else {
                        escaping = true;
                    }
                    break;
                case '{':
                    if (escaping) {
                        buf.append("\\{");
                    } else {
                        buf.append('(');
                        braceDepth++;
                    }
                    escaping = false;
                    break;
                case '}':
                    if (braceDepth > 0 && !escaping) {
                        buf.append(')');
                        braceDepth--;
                    } else if (escaping) {
                        buf.append("\\}");
                    } else {
                        buf.append("}");
                    }
                    escaping = false;
                    break;
                case ',':
                    if (braceDepth > 0 && !escaping) {
                        buf.append('|');
                    } else if (escaping) {
                        buf.append("\\,");
                    } else {
                        buf.append(",");
                    }
                    break;
                default:
                    escaping = false;
                    buf.append(c);
            }
        }
        return buf.toString();
    }

    @CheckForNull
    public String getDescription() {
        return description;
    }

    @NonNull
    public Secret getKey() {
        return secretKey;
    }

    public boolean isCanPing() {
        return canPing;
    }

    public boolean isCanThreadDump() {
        return canThreadDump;
    }

    public boolean isCanHealthCheck() {
        return canHealthCheck;
    }

    public boolean isCanMetrics() {
        return canMetrics;
    }

    public String getOrigins() {
        return origins;
    }

    public boolean isOriginAllowed(String origin) {
        if (originRegexs == null) {
            // idempotent
            if (StringUtils.equals("*", StringUtils.defaultIfBlank(origins, "*").trim())) {
                originRegexs = new String[]{".*"};
            } else {
                List<String> regexs = new ArrayList<String>();
                for (String pattern : StringUtils.split(origins, " ,")) {
                    if (StringUtils.isBlank(pattern)) {
                        continue;
                    }
                    String[] parts = StringUtils.split(pattern, ":");
                    if (parts.length > 3) {
                        regexs.add(globToRegex(parts[0]) + ":" + globToRegex(parts[1]) + ":" + globToRegex(parts[2]));
                    } else if (parts.length == 3) {
                        regexs.add(globToRegex(parts[0]) + ":" + globToRegex(parts[1]) + ":" + globToRegex(parts[2]));
                    } else if (parts.length == 2) {
                        if (parts[1].matches("^\\d{1,5}$")) {
                            // it's a port
                            regexs.add(".*:" + globToRegex(parts[0]) + ":" + parts[1]);
                        } else {
                            // it's a hostname
                            regexs.add(globToRegex(parts[0]) + ":" + globToRegex(parts[1]) + "(:.*)?");
                        }
                    } else if (parts.length == 1) {
                        // assume it matches the host name only
                        regexs.add(".*:" + globToRegex(pattern) + "(:.*)?");
                    }
                }
                originRegexs = regexs.toArray(new String[regexs.size()]);
            }

        }
        for (String regex : originRegexs) {
            if (origin.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MetricsAccessKey that = (MetricsAccessKey) o;

        if (canHealthCheck != that.canHealthCheck) {
            return false;
        }
        if (canMetrics != that.canMetrics) {
            return false;
        }
        if (canPing != that.canPing) {
            return false;
        }
        if (canThreadDump != that.canThreadDump) {
            return false;
        }
        if (description != null ? !description.equals(that.description) : that.description != null) {
            return false;
        }
        if (!secretKey.equals(that.secretKey)) {
            return false;
        }
        if (origins != null ? !origins.equals(that.origins) : that.origins != null) {
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return secretKey.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("MetricsAccessKey{");
        sb.append("key='").append(StringUtils.isNotEmpty(Secret.toString(secretKey)) ? "****" : "NULL").append('\'');
        sb.append(", description='").append(description).append('\'');
        sb.append(", canPing=").append(canPing);
        sb.append(", canHealthCheck=").append(canHealthCheck);
        sb.append(", canMetrics=").append(canMetrics);
        sb.append(", canThreadDump=").append(canThreadDump);
        sb.append(", origins='").append(origins).append('\'');
        sb.append('}');
        return sb.toString();
    }

    /**
     * Called when object has been deserialized from a stream.
     *
     * @return {@code this}, or a replacement for {@code this}.
     * @throws ObjectStreamException if the object cannot be restored.
     * @see <a href="http://download.oracle.com/javase/1.3/docs/guide/serialization/spec/input.doc6.html">The Java Object Serialization Specification</a>
     */
    private Object readResolve() throws ObjectStreamException {
        if (StringUtils.isNotEmpty(this.key)) {
            this.secretKey = Secret.fromString(this.key);
            this.key = null;
            DescriptorImpl.keyConverted = true;
        }
        return this;
    }

    /**
     * An extension point that allows for plugins to provide their own set of access keys.
     */
    public static interface Provider extends ExtensionPoint, Serializable {
        @NonNull
        List<MetricsAccessKey> getAccessKeys();

        /**
         * Returns the definition of the specific access key. Note that all entries in {@link #getAccessKeys()} must
         * be returned by this method, but it may also return additional entries.
         *
         * @param accessKey the access key to retrieve.
         * @return the access key.
         */
        @CheckForNull
        MetricsAccessKey getAccessKey(String accessKey);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<MetricsAccessKey> {

        private static final SecureRandom entropy = new SecureRandom();
        private static final char[] keyChars =
                "ABCEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
        /**
         * Used by {@link #readResolve()} to indicate that a key has been converted from String to {@link Secret}.
         */
        private static boolean keyConverted = false;
        @GuardedBy("this")
        private List<MetricsAccessKey> accessKeys;
        private transient volatile Set<String> accessKeySet;

        public DescriptorImpl() {
            super();
            keyIsNotConverted();
            load();
            if (keyConverted) {
                Logger.getLogger(MetricsAccessKey.class.getName()).info("Saving encrypted Metrics access key(s)");
                Timer.get().submit(this::save);
            }
        }

        /*package for testing*/ static boolean isKeyConverted() {
            return keyConverted;
        }

        private static void keyIsNotConverted() {
            keyConverted = false;
        }

        @NonNull
        public static String generateKey() {
            final int keyLength = 64;
            StringBuilder b = new StringBuilder(keyLength);
            for (int i = 0; i < keyLength; i++) {
                b.append(keyChars[entropy.nextInt(keyChars.length)]);
            }
            return b.toString();
        }

        @Override
        public synchronized boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
            final List<MetricsAccessKey> keys = req.bindJSONToList(MetricsAccessKey.class, json.get("accessKeys"));
            for (MetricsAccessKey accessKey : keys) {
                if (accessKey.getKey().getPlainText().isEmpty()) {
                    throw new FormException("Metrics access key cannot be empty. Make sure to generate it.", "accessKeys.key");
                }
            }
            this.accessKeys = keys;
            accessKeySet = null;
            save();
            return true;
        }

        @NonNull
        public synchronized List<MetricsAccessKey> getAccessKeys() {
            return Collections.unmodifiableList(new ArrayList<MetricsAccessKey>(
                    accessKeys == null ? Collections.<MetricsAccessKey>emptyList() : accessKeys
            ));
        }

        public void checkAccessKey(@CheckForNull String accessKey) {
            Set<String> accessKeySet = this.accessKeySet;
            if (accessKeySet == null) {
                reindexAccessKeys();
            }
            if (accessKeySet != null && !accessKeySet.contains(accessKey)) {
                // slow check
                for (Provider p : ExtensionList.lookup(Provider.class)) {
                    if (((!(p instanceof AbstractProvider) || ((AbstractProvider) p).isMayHaveOnDemandKeys())
                            && p.getAccessKey(accessKey) != null)) {
                        return;
                    }
                }
                throw new AccessDeniedException(Messages.MetricsAccessKey_invalidAccessKey(accessKey));
            }
        }

        public boolean hasAccessKeyPing(@CheckForNull String accessKey) {
            checkAccessKey(accessKey);
            MetricsAccessKey key = getAccessKey(accessKey);
            return key != null && key.isCanPing();
        }

        public boolean hasAccessKeyThreadDump(@CheckForNull String accessKey) {
            checkAccessKey(accessKey);
            MetricsAccessKey key = getAccessKey(accessKey);
            return key != null && key.isCanThreadDump();
        }

        public boolean hasAccessKeyHealthCheck(@CheckForNull String accessKey) {
            checkAccessKey(accessKey);
            MetricsAccessKey key = getAccessKey(accessKey);
            return key != null && key.isCanHealthCheck();
        }

        public boolean hasAccessKeyMetrics(@CheckForNull String accessKey) {
            checkAccessKey(accessKey);
            MetricsAccessKey key = getAccessKey(accessKey);
            return key != null && key.isCanMetrics();
        }

        public void checkAccessKeyPing(@CheckForNull String accessKey) {
            if (!hasAccessKeyPing(accessKey)) {
                throw new AccessDeniedException(Messages.MetricsAccessKey_invalidAccessKey(accessKey));
            }
        }

        public void checkAccessKeyThreadDump(@CheckForNull String accessKey) {
            if (!hasAccessKeyThreadDump(accessKey)) {
                throw new AccessDeniedException(Messages.MetricsAccessKey_invalidAccessKey(accessKey));
            }
        }

        public void checkAccessKeyHealthCheck(@CheckForNull String accessKey) {
            if (!hasAccessKeyHealthCheck(accessKey)) {
                throw new AccessDeniedException(Messages.MetricsAccessKey_invalidAccessKey(accessKey));
            }
        }

        public void checkAccessKeyMetrics(@CheckForNull String accessKey) {
            if (!hasAccessKeyMetrics(accessKey)) {
                throw new AccessDeniedException(Messages.MetricsAccessKey_invalidAccessKey(accessKey));
            }
        }

        public MetricsAccessKey getAccessKey(String accessKey) {
            for (Provider p : ExtensionList.lookup(Provider.class)) {
                MetricsAccessKey key = p.getAccessKey(accessKey);
                if (key != null) {
                    return key;
                }
            }
            synchronized (this) {
                for (MetricsAccessKey k : accessKeys) {
                    if (StringUtils.equals(accessKey, Secret.toString(k.getKey()))) {
                        return k;
                    }
                }
            }
            return null;
        }

        @RequirePOST
        public HttpResponse doGenerateNewToken() {
            Jenkins.get().checkPermission(Jenkins.MANAGE);

            Map<String, Object> data = new HashMap<>();
            data.put("tokenValue", DescriptorImpl.generateKey());
            return HttpResponses.okJSON(data);
        }

        /**
         *
         * Setter for the list of access keys
         *
         * @param accessKeys the list of  access keys to configure
         */
        @DataBoundSetter
        public synchronized void setAccessKeys(List<MetricsAccessKey> accessKeys) {
          this.accessKeys = accessKeys;
        }

        public HttpResponse cors(@CheckForNull String accessKey, final HttpResponse resp) {
            final MetricsAccessKey key = getAccessKey(accessKey);
            return key == null ? resp : new HttpResponse() {
                public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp, Object node)
                        throws IOException, ServletException {
                    String origin = req.getHeader("Origin");
                    if (StringUtils.isNotBlank(origin) && key.isOriginAllowed(origin)) {
                        rsp.addHeader("Access-Control-Allow-Origin", origin);
                        rsp.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
                        rsp.addHeader("Access-Control-Allow-Headers", "Accept, Authorization");
                        if ("OPTIONS".equals(req.getMethod())) {
                            rsp.setStatus(200);
                            return;
                        }
                    }
                    resp.generateResponse(req, rsp, node);
                }
            };
        }

        @Override
        public String getDisplayName() {
            return Messages.MetricsAccessKey_displayName();
        }

        public void reindexAccessKeys() {
            Set<String> accessKeySet = new HashSet<>();
            for (Provider p : ExtensionList.lookup(Provider.class)) {
                for (MetricsAccessKey k : p.getAccessKeys()) {
                    accessKeySet.add(Secret.toString(k.getKey()));
                }
            }
            synchronized (this) {
                if (accessKeys != null) {
                    for (MetricsAccessKey k : accessKeys) {
                        accessKeySet.add(Secret.toString(k.getKey()));
                    }
                }
                this.accessKeySet = accessKeySet;
            }
        }
    }

    /**
     * An extension point that allows for plugins to provide their own set of access keys.
     */
    public static abstract class AbstractProvider implements Provider {

        /**
         * Ensure consistent serialization.
         */
        private static final long serialVersionUID = 1L;
        /**
         * Tracks if {@link #getAccessKey(String)} has been overridden (which means that there may be keys that are
         * not iterable from the {@link #getAccessKeys()} method.
         */
        private transient Boolean mayHaveOnDemandKeys;

        /**
         * Returns {@code true} if {@link #getAccessKey(String)} has been overridden.
         *
         * @return {@code true} if {@link #getAccessKey(String)} has been overridden.
         */
        private boolean isMayHaveOnDemandKeys() {
            if (mayHaveOnDemandKeys == null) {
                // idempotent so no need for syncronization.
                boolean needsSlow;
                try {
                    Method method = getClass().getMethod("getAccessKey", String.class);
                    needsSlow = !method.getDeclaringClass().equals(Provider.class);
                } catch (NoSuchMethodException e) {
                    needsSlow = true;
                }
                this.mayHaveOnDemandKeys = needsSlow;
            }
            return mayHaveOnDemandKeys;
        }

        /**
         * Returns the definition of the specific access key. Note that all entries in {@link #getAccessKeys()} must
         * be returned by this method, but it may also return additional entries.
         *
         * @param accessKey the access key to retrieve.
         * @return the access key.
         */
        @CheckForNull
        public MetricsAccessKey getAccessKey(String accessKey) {
            for (MetricsAccessKey k : getAccessKeys()) {
                if (StringUtils.equals(accessKey, Secret.toString(k.getKey()))) {
                    return k;
                }
            }
            return null;
        }

    }

    /**
     * A provider that is a simple fixed list of keys.
     */
    public static class FixedListProviderImpl extends AbstractProvider {

        /**
         * Ensure consistent serialization.
         */
        private static final long serialVersionUID = 1L;
        /**
         * The access keys.
         */
        @CheckForNull
        private final List<MetricsAccessKey> accessKeys;

        public FixedListProviderImpl(@CheckForNull List<MetricsAccessKey> accessKeys) {
            this.accessKeys = accessKeys == null ? null : new ArrayList<MetricsAccessKey>(accessKeys);
        }

        /**
         * {@inheritDoc}
         */
        @NonNull
        public List<MetricsAccessKey> getAccessKeys() {
            return accessKeys == null
                    ? Collections.<MetricsAccessKey>emptyList()
                    : Collections.unmodifiableList(accessKeys);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("FixedListProviderImpl{");
            sb.append("accessKeys=").append(accessKeys);
            sb.append('}');
            return sb.toString();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            FixedListProviderImpl that = (FixedListProviderImpl) o;

            if (accessKeys != null ? !accessKeys.equals(that.accessKeys) : that.accessKeys != null) {
                return false;
            }

            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return accessKeys != null ? accessKeys.hashCode() : 0;
        }
    }
}
