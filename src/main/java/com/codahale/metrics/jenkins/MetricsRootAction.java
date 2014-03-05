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

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.json.HealthCheckModule;
import com.codahale.metrics.json.MetricsModule;
import com.codahale.metrics.jvm.ThreadDump;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import hudson.Extension;
import hudson.Util;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

/**
 * @author Stephen Connolly
 */
@Extension
public class MetricsRootAction implements UnprotectedRootAction {

    private final Admin admin = new Admin();
    private final ObjectMapper healthCheckMapper = new ObjectMapper().registerModule(new HealthCheckModule());
    private final ObjectMapper metricsMapper =
            new ObjectMapper().registerModule(new MetricsModule(TimeUnit.MINUTES, TimeUnit.SECONDS, true));

    private static boolean isAllHealthy(Map<String, HealthCheck.Result> results) {
        for (HealthCheck.Result result : results.values()) {
            if (!result.isHealthy()) {
                return false;
            }
        }
        return true;
    }

    private static ObjectWriter getWriter(ObjectMapper mapper, HttpServletRequest request) {
        final boolean prettyPrint = Boolean.parseBoolean(request.getParameter("pretty"));
        if (prettyPrint) {
            return mapper.writerWithDefaultPrettyPrinter();
        }
        return mapper.writer();
    }

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return null;
    }

    public String getUrlName() {
        return "codahale-metrics";
    }

    public Object getDynamic(String key) {
        Metrics.checkAccessKey(key);
        return admin;
    }

    public Object getCurrentUser() {
        Jenkins.getInstance().checkPermission(Metrics.VIEW);
        return admin;
    }

    @RequirePOST
    public HttpResponse doHealthcheck(@QueryParameter("key") String key) {
        Metrics.checkAccessKey(key);
        return new HealthCheckResponse(Metrics.healthCheckRegistry().runHealthChecks());
    }

    @RequirePOST
    public HttpResponse doMetrics(@QueryParameter("key") String key) {
        Metrics.checkAccessKey(key);
        return new MetricsResponse(Metrics.metricRegistry());
    }

    @RequirePOST
    public HttpResponse doPing(@QueryParameter("key") String key) {
        Metrics.checkAccessKey(key);
        return new PingResponse();
    }

    @RequirePOST
    public HttpResponse doThreads(@QueryParameter("key") String key) {
        Metrics.checkAccessKey(key);
        return new ThreadDumpResponse(new ThreadDump(ManagementFactory.getThreadMXBean()));
    }

    private static class PingResponse implements HttpResponse {
        private static final String CONTENT_TYPE = "text/plain";
        private static final String CONTENT = "pong";
        private static final String CACHE_CONTROL = "Cache-Control";
        private static final String NO_CACHE = "must-revalidate,no-cache,no-store";

        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader(CACHE_CONTROL, NO_CACHE);
            resp.setContentType(CONTENT_TYPE);
            final PrintWriter writer = resp.getWriter();
            try {
                writer.println(CONTENT);
            } finally {
                writer.close();
            }
        }
    }

    private static class ThreadDumpResponse implements HttpResponse {
        private static final String CONTENT_TYPE = "text/plain";
        private static final String CACHE_CONTROL = "Cache-Control";
        private static final String NO_CACHE = "must-revalidate,no-cache,no-store";
        private final ThreadDump threadDump;

        public ThreadDumpResponse(ThreadDump threadDump) {
            this.threadDump = threadDump;
        }

        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader(CACHE_CONTROL, NO_CACHE);
            resp.setContentType(CONTENT_TYPE);
            final OutputStream output = resp.getOutputStream();
            try {
                threadDump.dump(output);
            } finally {
                output.close();
            }
        }
    }

    public class Admin {

        public HttpResponse doHealthcheck() {
            return new HealthCheckResponse(Metrics.healthCheckRegistry().runHealthChecks());
        }

        public HttpResponse doMetrics() {
            return new MetricsResponse(Metrics.metricRegistry());
        }

        public HttpResponse doPing() {
            return new PingResponse();
        }

        public HttpResponse doThreads() {
            return new ThreadDumpResponse(new ThreadDump(ManagementFactory.getThreadMXBean()));
        }

        public HttpResponse doIndex() {
            return HttpResponses
                    .html("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"\n" +
                            "        \"http://www.w3.org/TR/html4/loose.dtd\">\n" +
                            "<html>\n" +
                            "<head>\n" +
                            "  <title>Metrics</title>\n" +
                            "</head>\n" +
                            "<body>\n" +
                            "  <h1>Operational Menu</h1>\n" +
                            "  <ul>\n" +
                            "    <li><a href=\"./metrics?pretty=true\">Metrics</a></li>\n" +
                            "    <li><a href=\"./ping\">Ping</a></li>\n" +
                            "    <li><a href=\"./threads\">Threads</a></li>\n" +
                            "    <li><a href=\"./healthcheck?pretty=true\">Healthcheck</a></li>\n" +
                            "  </ul>\n" +
                            "</body>\n" +
                            "</html>");
        }

    }

    private class HealthCheckResponse implements HttpResponse {
        private static final String JSONP_CONTENT_TYPE = "text/javascript";
        private static final String JSON_CONTENT_TYPE = "application/json";
        private static final String CACHE_CONTROL = "Cache-Control";
        private static final String NO_CACHE = "must-revalidate,no-cache,no-store";
        private final SortedMap<String, HealthCheck.Result> results;

        public HealthCheckResponse(SortedMap<String, HealthCheck.Result> results) {
            this.results = results;
        }

        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            boolean jsonp = StringUtils.isNotBlank(req.getParameter("callback"));
            String jsonpCallback = StringUtils.defaultIfBlank(req.getParameter("callback"), "callback");
            resp.setHeader(CACHE_CONTROL, NO_CACHE);
            resp.setContentType(jsonp ? JSONP_CONTENT_TYPE : JSON_CONTENT_TYPE);
            if (results.isEmpty()) {
                resp.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
            } else {
                if (isAllHealthy(results)) {
                    resp.setStatus(HttpServletResponse.SC_OK);
                } else {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }

            final OutputStream output = resp.getOutputStream();
            try {
                if (jsonp) {
                    output.write(jsonpCallback.getBytes("US-ASCII"));
                    output.write("(".getBytes("US-ASCII"));
                }
                output.write(getWriter(healthCheckMapper, req).writeValueAsBytes(results));
                if (jsonp) {
                    output.write(");".getBytes("US-ASCII"));
                }
            } finally {
                output.close();
            }
        }
    }

    private class MetricsResponse implements HttpResponse {
        private static final String JSONP_CONTENT_TYPE = "text/javascript";
        private static final String JSON_CONTENT_TYPE = "application/json";
        private static final String CACHE_CONTROL = "Cache-Control";
        private static final String NO_CACHE = "must-revalidate,no-cache,no-store";
        private final MetricRegistry registry;

        private MetricsResponse(MetricRegistry registry) {
            this.registry = registry;
        }

        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            boolean jsonp = StringUtils.isNotBlank(req.getParameter("callback"));
            String jsonpCallback = StringUtils.defaultIfBlank(req.getParameter("callback"), "callback");
            String prefix = Util.fixEmptyAndTrim(req.getParameter("prefix"));
            String postfix = Util.fixEmptyAndTrim(req.getParameter("postfix"));
            resp.setHeader(CACHE_CONTROL, NO_CACHE);
            resp.setContentType(jsonp ? JSONP_CONTENT_TYPE : JSON_CONTENT_TYPE);
            resp.setStatus(HttpServletResponse.SC_OK);

            final OutputStream output = resp.getOutputStream();
            try {
                if (jsonp) {
                    output.write(jsonpCallback.getBytes("US-ASCII"));
                    output.write("(".getBytes("US-ASCII"));
                }
                output.write(getWriter(metricsMapper, req)
                        .writeValueAsBytes(prefix == null && postfix == null
                                ? registry
                                : new NameRewriterMetricRegistry(prefix, registry, postfix)));
                if (jsonp) {
                    output.write(");".getBytes("US-ASCII"));
                }
            } finally {
                output.close();
            }
        }
    }
}
