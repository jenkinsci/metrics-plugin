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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import net.sf.json.JSONObject;
import org.acegisecurity.AccessDeniedException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.concurrent.GuardedBy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Stephen Connolly
 */
public class MetricsAccessKey extends AbstractDescribableImpl<MetricsAccessKey> {
    @NonNull
    private final String key;
    @CheckForNull
    private final String description;

    @DataBoundConstructor
    public MetricsAccessKey(String description, String key) {
        this.description = Util.fixEmptyAndTrim(description);
        key = Util.fixEmptyAndTrim(key);
        this.key = key == null ? DescriptorImpl.generateKey() : key;
    }

    @CheckForNull
    public String getDescription() {
        return description;
    }

    @NonNull
    public String getKey() {
        return key;
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

        @Override
        public String getDisplayName() {
            return Messages.MetricsAccessKey_displayName();
        }

        @NonNull
        public static String generateKey() {
            StringBuilder b = new StringBuilder(64);
            for (int i = 0; i < 64; i++) {
                b.append(keyChars[entropy.nextInt(keyChars.length)]);
            }
            return b.toString();
        }
    }
}
