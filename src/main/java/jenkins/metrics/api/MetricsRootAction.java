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

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.*;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import jenkins.metrics.util.NameRewriterMetricRegistry;
import com.codahale.metrics.json.HealthCheckModule;
import com.codahale.metrics.json.MetricsModule;
import com.codahale.metrics.jvm.ThreadDump;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import hudson.Extension;
import hudson.Util;
import hudson.model.PeriodicWork;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import hudson.util.IOUtils;
import jenkins.metrics.util.ExponentialLeakyBucket;
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
import java.io.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author Stephen Connolly
 */
@Extension
public class MetricsRootAction implements UnprotectedRootAction {

    public static final TimeUnit RATE_UNIT = TimeUnit.MINUTES;
    public static final TimeUnit DURATION_UNIT = TimeUnit.SECONDS;
    private final Pseudoservlet currentUser = new CurrentUserPseudoservlet();
    private final ObjectMapper healthCheckMapper = new ObjectMapper().registerModule(new HealthCheckModule());
    private final ObjectMapper metricsMapper =
            new ObjectMapper().registerModule(new MetricsModule(RATE_UNIT, DURATION_UNIT, true));

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

    @SuppressWarnings("unchecked")
    private static void requireCorrectMethod(@NonNull StaplerRequest req) throws IllegalAccessException {
        if (!(req.getMethod().equals("POST")
                || (req.getMethod().equals("OPTIONS") && StringUtils.isNotBlank(req.getHeader("Origin")))
                || (req.getMethod().equals("GET") && getKeyFromAuthorizationHeader(req) != null)
        )) {
            throw new IllegalAccessException("POST is required");
        }
    }

    @CheckForNull
    private static String getKeyFromAuthorizationHeader(@NonNull StaplerRequest req) throws IllegalAccessException {
        for (Object o : Collections.list(req.getHeaders("Authorization"))) {
            if (o instanceof String && ((String) o).startsWith("Jenkins-Metrics-Key ")) {
                return Util.fixEmptyAndTrim(((String) o).substring("Jenkins-Metrics-Key ".length()));
            }
        }
        return null;
    }

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return null;
    }

    public String getUrlName() {
        return "metrics";
    }

    public Object getDynamic(final String key) {
        Metrics.checkAccessKey(key);
        return new AccessKeyPseudoservlet(key);
    }

    public Object getCurrentUser() {
        Jenkins.getInstance().checkPermission(Metrics.VIEW);
        return currentUser;
    }

    public HttpResponse doHealthcheck(StaplerRequest req, @QueryParameter("key") String key)
            throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyHealthCheck(key);
        return Metrics.cors(key, new HealthCheckResponse(Metrics.healthCheckRegistry().runHealthChecks()));
    }

    public HttpResponse doMetrics(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyMetrics(key);
        return Metrics.cors(key, new MetricsResponse(Metrics.metricRegistry()));
    }

    public HttpResponse doMetricsHistory(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyMetrics(key);
        return Metrics.cors(key, new MetricsHistoryResponse());
    }

    public HttpResponse doPing(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyPing(key);
        return Metrics.cors(key, new PingResponse());
    }

    @RequirePOST
    public HttpResponse doThreads(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyThreadDump(key);
        return Metrics.cors(key, new ThreadDumpResponse(new ThreadDump(ManagementFactory.getThreadMXBean())));
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

    public class Pseudoservlet {

        public HttpResponse doHealthcheck() {
            return new HealthCheckResponse(Metrics.healthCheckRegistry().runHealthChecks());
        }

        public HttpResponse doMetrics() {
            return new MetricsResponse(Metrics.metricRegistry());
        }

        public HttpResponse doMetricsHistory() {
            return new MetricsHistoryResponse();
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

    private class MetricsHistoryResponse implements HttpResponse {
        private static final String JSONP_CONTENT_TYPE = "text/javascript";
        private static final String JSON_CONTENT_TYPE = "application/json";
        private static final String CACHE_CONTROL = "Cache-Control";
        private static final String NO_CACHE = "must-revalidate,no-cache,no-store";

        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            boolean jsonp = StringUtils.isNotBlank(req.getParameter("callback"));
            String jsonpCallback = StringUtils.defaultIfBlank(req.getParameter("callback"), "callback");
            resp.setHeader(CACHE_CONTROL, NO_CACHE);
            resp.setContentType(jsonp ? JSONP_CONTENT_TYPE : JSON_CONTENT_TYPE);
            resp.setStatus(HttpServletResponse.SC_OK);

            final OutputStream output = resp.getOutputStream();
            try {
                if (jsonp) {
                    output.write(jsonpCallback.getBytes("US-ASCII"));
                    output.write("(".getBytes("US-ASCII"));
                }
                Sampler sampler = Jenkins.getInstance().getExtensionList(PeriodicWork.class).get(Sampler.class);
                Map<Date, Object> sample = sampler == null ? null : sampler.sample();
                output.write(getWriter(metricsMapper, req).writeValueAsBytes(sample));
                if (jsonp) {
                    output.write(");".getBytes("US-ASCII"));
                }
            } finally {
                output.close();
            }
        }
    }

    public class CurrentUserPseudoservlet extends Pseudoservlet {
        @Override
        public HttpResponse doHealthcheck() {
            Jenkins.getInstance().checkPermission(Metrics.HEALTH_CHECK);
            return super.doHealthcheck();
        }

        @Override
        public HttpResponse doIndex() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doIndex();
        }

        @Override
        public HttpResponse doMetrics() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doMetrics();
        }

        @Override
        public HttpResponse doMetricsHistory() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doMetricsHistory();
        }

        @Override
        public HttpResponse doPing() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doPing();
        }

        @Override
        public HttpResponse doThreads() {
            Jenkins.getInstance().checkPermission(Metrics.THREAD_DUMP);
            return super.doThreads();
        }
    }

    public class AccessKeyPseudoservlet extends Pseudoservlet {
        private final String key;

        public AccessKeyPseudoservlet(String key) {
            this.key = key;
        }

        @Override
        public HttpResponse doHealthcheck() {
            Metrics.checkAccessKeyHealthCheck(key);
            return Metrics.cors(key, super.doHealthcheck());
        }

        @Override
        public HttpResponse doMetrics() {
            Metrics.checkAccessKeyMetrics(key);
            return Metrics.cors(key, super.doMetrics());
        }

        @Override
        public HttpResponse doMetricsHistory() {
            Metrics.checkAccessKeyMetrics(key);
            return Metrics.cors(key, super.doMetricsHistory());
        }

        @Override
        public HttpResponse doPing() {
            Metrics.checkAccessKeyPing(key);
            return Metrics.cors(key, super.doPing());
        }

        @Override
        public HttpResponse doThreads() {
            Metrics.checkAccessKeyThreadDump(key);
            return Metrics.cors(key, super.doThreads());
        }
    }

    @Extension
    public static class Sampler extends PeriodicWork {

        private final ExponentialLeakyBucket<Sample> bucket = new ExponentialLeakyBucket<Sample>(128, 0.005);
        private final ObjectMapper mapper;

        public Sampler() {
            mapper = new ObjectMapper();
            mapper.registerModule(new MetricsModule(RATE_UNIT, DURATION_UNIT, false));
        }

        public Map<Date, Object> sample() {
            Map<Date, Object> result = new TreeMap<Date, Object>();
            ObjectReader reader = mapper.reader(new JsonNodeFactory(false));
            for (Sample s : bucket.values()) {
                JsonNode value = s.getValue(reader);
                if (value != null) {
                    result.put(s.getTime(), value);
                }
            }
            return result;
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(30);
        }

        @Override
        protected void doRun() throws Exception {
            ObjectWriter writer = mapper.writer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            try {
                GZIPOutputStream gzos = null;
                try {
                    gzos = new GZIPOutputStream(baos);
                    writer.writeValue(gzos, Metrics.metricRegistry());
                } finally {
                    IOUtils.closeQuietly(gzos);
                }
            } finally {
                baos.close();
            }
            bucket.add(new Sample(System.currentTimeMillis(), baos.toByteArray()));
        }

        @SuppressWarnings(value = "EI_EXPOSE_REP2")
        public static class Sample {
            private final long t;
            private final byte[] v;

            public Sample(long t, byte[] v) {
                this.t = t;
                this.v = v;
            }

            public Date getTime() {
                return new Date(t);
            }

            public JsonNode getValue(ObjectReader reader) {
                GZIPInputStream gzis = null;
                try {
                    gzis = new GZIPInputStream(new ByteArrayInputStream(v));
                    return reader.readTree(gzis);
                } catch (JsonProcessingException e) {
                    return null;
                } catch (IOException e) {
                    return null;
                } finally {
                    IOUtils.closeQuietly(gzis);
                }
            }
        }

    }
}
