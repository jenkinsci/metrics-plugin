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

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import hudson.ExtensionList;
import hudson.ExtensionListListener;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;

/**
 * An {@link ExtensionListListener} that automatically registers health checks from dynamically installed plugins.
 */
class HealthCheckProviderListener extends ExtensionListListener {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(HealthCheckProviderListener.class.getName());
    /**
     * The metric providers that have been registered already.
     */
    @GuardedBy("self")
    private final IdentityHashMap<HealthCheckProvider, Void> registered = new IdentityHashMap<>();
    /**
     * The extension list.
     */
    private final ExtensionList<HealthCheckProvider> extensionList;
    /**
     * The registry
     */
    private final HealthCheckRegistry registry;

    /**
     * Constructor.
     *
     * @param extensionList the extension list being observed.
     * @param registry      the registry into which new providers should be registered.
     */
    private HealthCheckProviderListener(ExtensionList<HealthCheckProvider> extensionList,
                                        HealthCheckRegistry registry) {
        this.extensionList = extensionList;
        this.registry = registry;
    }

    /**
     * Attaches to the extension list and registers all providers.
     *
     * @param registry the registry into which new providers should be registered.
     */
    public static void attach(HealthCheckRegistry registry) {
        HealthCheckProviderListener listener =
                new HealthCheckProviderListener(ExtensionList.lookup(HealthCheckProvider.class), registry);
        listener.onChange();
        listener.extensionList.addListener(listener);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onChange() {
        synchronized (registered) {
            for (HealthCheckProvider p : extensionList) {
                if (!registered.containsKey(p)) {
                    LOGGER.log(Level.FINER, "Registering health check provider {0} (type {1})",
                            new Object[]{p, p.getClass()});
                    Map<String, HealthCheck> healthChecks = p.getHealthChecks();
                    for (Map.Entry<String, HealthCheck> c : healthChecks.entrySet()) {
                        registry.register(c.getKey(), c.getValue());
                    }
                    LOGGER.log(Level.FINER, "Registered health check provider {0} (type {1}) with {2} checks: {3}",
                            new Object[]{p, p.getClass(), healthChecks.size(), healthChecks.keySet()});
                    registered.put(p, null);
                }
            }
        }
    }

}
