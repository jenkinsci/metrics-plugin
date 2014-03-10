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

package com.codahale.metrics.jenkins;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
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
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.concurrent.GuardedBy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author Stephen Connolly
 */
public class MetricsAccessKey extends AbstractDescribableImpl<MetricsAccessKey> {
    @NonNull
    private final String key;
    @CheckForNull
    private final String description;
    private final boolean canPing;
    private final boolean canThreadDump;
    private final boolean canHealthCheck;
    private final boolean canMetrics;

    public MetricsAccessKey(String description, String key) {
        this(description, key, true, false, false, true);
    }

    @DataBoundConstructor
    public MetricsAccessKey(String description, String key, boolean canPing, boolean canThreadDump,
                            boolean canHealthCheck, boolean canMetrics) {
        this.description = Util.fixEmptyAndTrim(description);
        key = Util.fixEmptyAndTrim(key);
        this.key = key == null ? DescriptorImpl.generateKey() : key;
        this.canPing = canPing;
        this.canThreadDump = canThreadDump;
        this.canHealthCheck = canHealthCheck;
        this.canMetrics = canMetrics;
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
            if (accessKeySet == null) {
                accessKeySet = new HashSet<String>();
                for (Provider p : Jenkins.getInstance().getExtensionList(Provider.class)) {
                    for (MetricsAccessKey k : p.getAccessKeys()) {
                        accessKeySet.add(k.getKey());
                    }
                }
                synchronized (this) {
                    for (MetricsAccessKey k : accessKeys) {
                        accessKeySet.add(k.getKey());
                    }
                    this.accessKeySet = accessKeySet; // will be idempotent
                }
            }
            if (!accessKeySet.contains(accessKey)) {
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

        private MetricsAccessKey getAccessKey(String accessKey) {
            MetricsAccessKey key = null;
            outer:
            for (Provider p : Jenkins.getInstance().getExtensionList(Provider.class)) {
                for (MetricsAccessKey k : p.getAccessKeys()) {
                    if (StringUtils.equals(accessKey, k.getKey())) {
                        key = k;
                        break outer;
                    }
                }
            }
            if (key == null) {
                synchronized (this) {
                    for (MetricsAccessKey k : accessKeys) {
                        if (StringUtils.equals(accessKey, k.getKey())) {
                            key = k;
                            break;
                        }
                    }
                }
            }
            return key;
        }

        @Override
        public String getDisplayName() {
            return Messages.MetricsAccessKey_displayName();
        }

        public void reindexAccessKeys() {
            Set<String> accessKeySet = new HashSet<String>();
            for (Provider p : Jenkins.getInstance().getExtensionList(Provider.class)) {
                for (MetricsAccessKey k : p.getAccessKeys()) {
                    accessKeySet.add(k.getKey());
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
    public static abstract class Provider implements ExtensionPoint {
        public abstract List<MetricsAccessKey> getAccessKeys();
    }
}
