/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
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

import com.codahale.metrics.MetricRegistry;
import hudson.ExtensionList;
import hudson.ExtensionListListener;
import java.util.IdentityHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;

/**
 * An {@link ExtensionListListener} that automatically registers metrics from dynamically installed plugins.
 */
class MetricProviderListener extends ExtensionListListener {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(MetricProviderListener.class.getName());
    /**
     * The metric providers that have been registered already.
     */
    @GuardedBy("self")
    private final IdentityHashMap<MetricProvider, Void> registered = new IdentityHashMap<>();
    /**
     * The extension list.
     */
    private final ExtensionList<MetricProvider> extensionList;
    /**
     * The registry
     */
    private final MetricRegistry registry;

    /**
     * Constructor.
     *
     * @param extensionList the extension list being observed.
     * @param registry      the registry into which new providers should be registered.
     */
    private MetricProviderListener(ExtensionList<MetricProvider> extensionList,
                                   MetricRegistry registry) {
        this.extensionList = extensionList;
        this.registry = registry;
    }

    /**
     * Attaches to the extension list and registers all providers.
     *
     * @param registry      the registry into which new providers should be registered.
     */
    public static void attach(MetricRegistry registry) {
        MetricProviderListener listener =
                new MetricProviderListener(ExtensionList.lookup(MetricProvider.class), registry);
        listener.onChange();
        listener.extensionList.addListener(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onChange() {
        synchronized (registered) {
            for (MetricProvider p : extensionList) {
                if (!registered.containsKey(p)) {
                    LOGGER.log(Level.FINER, "Registering metric provider {0} (type {1})",
                            new Object[]{p, p.getClass()});
                    registry.registerAll(p.getMetricSet());
                    registered.put(p, null);
                }
            }
        }
    }

}
