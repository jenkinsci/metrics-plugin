/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;

import java.util.Map;
import java.util.Map.Entry;

@Extension
public class HealthCheckProviderForTesting extends HealthCheckProvider {

    public static int runs;

    @NonNull
    @Override
    public Map<String, HealthCheck> getHealthChecks() {
        return checks(check(1), check(2), check(3), check(4), check(5), check(6));
    }

    private Entry<String, HealthCheck> check(int i) {
        return check("short-running-health-check-" + i, new HealthCheck() {
            @Override
            protected Result check() throws Exception {
                runs++;
                Thread.sleep(1 * 1000);
                return Result.unhealthy("some error message");
            }
        });
    }
}
