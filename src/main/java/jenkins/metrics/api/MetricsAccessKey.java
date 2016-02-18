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
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.acegisecurity.AccessDeniedException;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.concurrent.GuardedBy;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Stephen Connolly
 */
public class MetricsAccessKey extends AbstractDescribableImpl<MetricsAccessKey> implements Serializable {
    private static final long serialVersionUID = 1L;
    @NonNull
    private final String key;
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
        this(description, key, true, false, false, true, null);
    }

    @DataBoundConstructor
    public MetricsAccessKey(String description, String key, boolean canPing, boolean canThreadDump,
                            boolean canHealthCheck, boolean canMetrics, String origins) {
        this.description = Util.fixEmptyAndTrim(description);
        key = Util.fixEmptyAndTrim(key);
        this.key = key == null ? DescriptorImpl.generateKey() : key;
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
    public String getKey() {
        return key;
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
        if (!key.equals(that.key)) {
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
        return key.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("MetricsAccessKey{");
        sb.append("key='").append(key).append('\'');
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
        @GuardedBy("this")
        private List<MetricsAccessKey> accessKeys;
        private transient volatile Set<String> accessKeySet;

        public DescriptorImpl() {
            super();
            load();
        }

        @NonNull
        public static String generateKey() {
            StringBuilder b = new StringBuilder(64);
            for (int i = 0; i < 64; i++) {
                b.append(keyChars[entropy.nextInt(keyChars.length)]);
            }
            return b.toString();
        }

        @Override
        public synchronized boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            accessKeys = req.bindJSONToList(MetricsAccessKey.class, json.get("accessKeys"));
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
            Jenkins jenkins = Jenkins.getInstance();
            if (accessKeySet == null) {
                accessKeySet = new HashSet<String>();
                if (jenkins != null) {
                    for (Provider p : jenkins.getExtensionList(Provider.class)) {
                        for (MetricsAccessKey k : p.getAccessKeys()) {
                            accessKeySet.add(k.getKey());
                        }
                    }
                }
                synchronized (this) {
                    if (accessKeys != null) {
                        for (MetricsAccessKey k : accessKeys) {
                            accessKeySet.add(k.getKey());
                        }
                    }
                    this.accessKeySet = accessKeySet; // will be idempotent
                }
            }
            if (!accessKeySet.contains(accessKey)) {
                // slow check
                if (jenkins != null) {
                    for (Provider p : jenkins.getExtensionList(Provider.class)) {
                        if (((!(p instanceof AbstractProvider) || ((AbstractProvider) p).isMayHaveOnDemandKeys())
                                && p.getAccessKey(accessKey) != null)) {
                            return;
                        }
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
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins != null) {
                for (Provider p : jenkins.getExtensionList(Provider.class)) {
                    MetricsAccessKey key = p.getAccessKey(accessKey);
                    if (key != null) {
                        return key;
                    }
                }
            }
            synchronized (this) {
                for (MetricsAccessKey k : accessKeys) {
                    if (StringUtils.equals(accessKey, k.getKey())) {
                        return k;
                    }
                }
            }
            return null;
        }

        public HttpResponse cors(@CheckForNull String accessKey, final HttpResponse resp) {
            final MetricsAccessKey key = getAccessKey(accessKey);
            return key == null ? resp : new HttpResponse() {
                public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node)
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
            Set<String> accessKeySet = new HashSet<String>();
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins != null) {
                for (Provider p : jenkins.getExtensionList(Provider.class)) {
                    for (MetricsAccessKey k : p.getAccessKeys()) {
                        accessKeySet.add(k.getKey());
                    }
                }
            }
            synchronized (this) {
                if (accessKeys != null) {
                    for (MetricsAccessKey k : accessKeys) {
                        accessKeySet.add(k.getKey());
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
                if (StringUtils.equals(accessKey, k.getKey())) {
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
