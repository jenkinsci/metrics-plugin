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
import com.codahale.metrics.json.HealthCheckModule;
import com.codahale.metrics.json.MetricsModule;
import com.codahale.metrics.jvm.ThreadDump;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.PeriodicWork;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import hudson.util.IOUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jenkins.metrics.util.ExponentialLeakyBucket;
import jenkins.metrics.util.NameRewriterMetricRegistry;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Root action that exposes the metrics via the REST UI.
 */
@Extension
public class MetricsRootAction implements UnprotectedRootAction {

    /**
     * The time units to express rates in, that is all rates are events per minute.
     */
    public static final TimeUnit RATE_UNIT = TimeUnit.MINUTES;
    /**
     * The time unit to express durations in, that is all durations are in seconds.
     */
    public static final TimeUnit DURATION_UNIT = TimeUnit.SECONDS;
    /**
     * The {@code Cache-Control} header name.
     */
    private static final String CACHE_CONTROL = "Cache-Control";
    /**
     * Regex to parse the {@code max-age=...} component of a {@code Cache-Control} HTTP request header.
     */
    private static final Pattern CACHE_CONTROL_MAX_AGE = Pattern.compile("[\\s,]?max-age=(\\d+)[\\s,]?");
    /**
     * The {@code Cache-Control} directive to indicate no caching.
     */
    private static final String NO_CACHE = "must-revalidate,no-cache,no-store";
    /**
     * The {@link String#format(String,Object...)} format for a {@code Cache-Control} directive to indicate caching
     * with a max-age.
     */
    private static final String MAX_AGE = "must-revalidate,private,max-age=%d";
    /**
     * The {@code Content-Type} for plain text.
     */
    private static final String PLAIN_TEXT_CONTENT_TYPE = "text/plain";
    /**
     * The {@code Content-Type} for JSONP.
     */
    private static final String JSONP_CONTENT_TYPE = "text/javascript";
    /**
     * The {@code Content-Type} for JSON.
     */
    private static final String JSON_CONTENT_TYPE = "application/json";
    /**
     * Singleton instance to bind to the {@code /currentUser} URL
     */
    private final Pseudoservlet currentUser = new CurrentUserPseudoservlet();
    /**
     * The {@link ObjectMapper} to use when converting health checks to JSON.
     */
    private final ObjectMapper healthCheckMapper = new ObjectMapper().registerModule(new HealthCheckModule());
    /**
     * The {@link ObjectMapper} to use when converting metrics to JSON.
     */
    private final ObjectMapper metricsMapper =
            new ObjectMapper().registerModule(new MetricsModule(RATE_UNIT, DURATION_UNIT, true));

    /**
     * Utility method to check if health check results are all reporting healthy.
     *
     * @param results the results.
     * @return {@code true} if all are healthy.
     */
    private static boolean isAllHealthy(Map<String, HealthCheck.Result> results) {
        for (HealthCheck.Result result : results.values()) {
            if (!result.isHealthy()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Utility wrapper to construct the writer to use for a {@link HttpServletResponse} based on the
     * {@link HttpServletRequest}.
     *
     * @param mapper  the {@link ObjectMapper} to use.
     * @param request the {@link HttpServletRequest} to respond to.
     * @return the {@link ObjectWriter} to use for the response.
     */
    private static ObjectWriter getWriter(ObjectMapper mapper, HttpServletRequest request) {
        final boolean prettyPrint = Boolean.parseBoolean(request.getParameter("pretty"));
        if (prettyPrint) {
            return mapper.writerWithDefaultPrettyPrinter();
        }
        return mapper.writer();
    }

    /**
     * Utility method to check a request against the CORS requirements.
     *
     * @param req the request to check.
     * @throws IllegalAccessException if the request is not a POST or OPTIONS (with Origin header) or a GET request
     *                                with the appropriate authorization key.
     */
    @SuppressWarnings("unchecked")
    private static void requireCorrectMethod(@NonNull StaplerRequest req) throws IllegalAccessException {
        if (!(req.getMethod().equals("POST")
                || (req.getMethod().equals("OPTIONS") && StringUtils.isNotBlank(req.getHeader("Origin")))
                || (req.getMethod().equals("GET") && getKeyFromAuthorizationHeader(req) != null)
        )) {
            throw new IllegalAccessException("POST is required");
        }
    }

    /**
     * Utility method to check if the request has an authorization key in the header.
     *
     * @param req the request.
     * @return the authorization key or {@code null}.
     */
    @CheckForNull
    private static String getKeyFromAuthorizationHeader(@NonNull StaplerRequest req) {
        for (Object o : Collections.list(req.getHeaders("Authorization"))) {
            if (o instanceof String && ((String) o).startsWith("Jenkins-Metrics-Key ")) {
                return Util.fixEmptyAndTrim(((String) o).substring("Jenkins-Metrics-Key ".length()));
            }
        }
        return null;
    }

    /**
     * Parses a {@link StaplerRequest} and extracts the {code max-age=...} directive from the client headers if present.
     *
     * @param req the request.
     * @return the max-age (in milliseconds) or -1 if not present.
     */
    @SuppressWarnings("unchecked")
    private static long getCacheControlMaxAge(StaplerRequest req) {
        long maxAge = -1L;
        for (String value : Collections.list((Enumeration<String>) req.getHeaders(CACHE_CONTROL))) {
            Matcher matcher = CACHE_CONTROL_MAX_AGE.matcher(value);
            while (matcher.find()) {
                maxAge = TimeUnit.SECONDS.toMillis(Long.parseLong(matcher.group(1)));
            }
        }
        return Math.max(-1L, maxAge);
    }

    /**
     * {@inheritDoc}
     */
    public String getIconFileName() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public String getDisplayName() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public String getUrlName() {
        return "metrics";
    }

    /**
     * Binds the {@link Pseudoservlet} for a metric access keys to the URL {@code /metrics/{key}}
     *
     * @param key the key.
     * @return the {@link Pseudoservlet}
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public Object getDynamic(final String key) {
        Metrics.checkAccessKey(key);
        return new AccessKeyPseudoservlet(key);
    }

    /**
     * Binds the {@link Pseudoservlet} for the current user to the URL {@code /metrics/currentUser}
     *
     * @return the {@link Pseudoservlet}
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public Object getCurrentUser() {
        Jenkins.getInstance().checkPermission(Metrics.VIEW);
        return currentUser;
    }

    /**
     * Binds the health checks to the CORS aware URL {@code /metrics/healthcheck} where the metrics access key is
     * provided in the form field {@code key} or an {@code Authorization: Jenkins-Metrics-Key {key}} header
     *
     * @param req the request
     * @param key the key from the form field.
     * @return the {@link HttpResponse}
     * @throws IllegalAccessException if the access attempt is invalid.
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public HttpResponse doHealthcheck(StaplerRequest req, @QueryParameter("key") String key)
            throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyHealthCheck(key);
        long ifModifiedSince = req.getDateHeader("If-Modified-Since");
        long maxAge = getCacheControlMaxAge(req);
        Metrics.HealthCheckData data = Metrics.getHealthCheckData();
        if (data == null || (maxAge != -1 && data.getLastModified() + maxAge < System.currentTimeMillis())) {
            data = new Metrics.HealthCheckData(Metrics.healthCheckRegistry().runHealthChecks());
        } else if (ifModifiedSince != -1 && data.getLastModified() < ifModifiedSince) {
            return Metrics.cors(key, HttpResponses.status(HttpServletResponse.SC_NOT_MODIFIED));
        }
        return Metrics.cors(key, new HealthCheckResponse(data));
    }

    /**
     * Condense the health check into one bit of information
     * for frontend reverse proxies like haproxy.
     *
     * Other health check calls requires authentication, which
     * is not suitable for the haproxy use. But this endpoint
     * only exposes one bit information, it's deemed OK to be exposed
     * unsecurely.
     *
     * return status 200 if everything is OK, 503 (service unavailable) otherwise
     *
     * @param req the request
     * @return the HTTP response
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public HttpResponse doHealthcheckOk(StaplerRequest req) {
        long ifModifiedSince = req.getDateHeader("If-Modified-Since");
        long maxAge = getCacheControlMaxAge(req);
        Metrics.HealthCheckData data = Metrics.getHealthCheckData();
        if (data == null || (maxAge != -1 && data.getLastModified() + maxAge < System.currentTimeMillis())) {
            data = new Metrics.HealthCheckData(Metrics.healthCheckRegistry().runHealthChecks());
        } else if (ifModifiedSince != -1 && data.getLastModified() < ifModifiedSince) {
            return HttpResponses.status(HttpServletResponse.SC_NOT_MODIFIED);
        }
        for (HealthCheck.Result result : data.getResults().values()) {
            if (!result.isHealthy()) {
                return new StatusResponse(HttpServletResponse.SC_SERVICE_UNAVAILABLE, data.getLastModified(),
                        data.getExpires());
            }
        }
        return new StatusResponse(HttpServletResponse.SC_OK, data.getLastModified(), data.getExpires());
    }

    /**
     * Binds the metrics to the CORS aware URL {@code /metrics/metrics} where the metrics access key is
     * provided in the form field {@code key} or an {@code Authorization: Jenkins-Metrics-Key {key}} header
     *
     * @param req the request
     * @param key the key from the form field.
     * @return the {@link HttpResponse}
     * @throws IllegalAccessException if the access attempt is invalid.
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public HttpResponse doMetrics(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyMetrics(key);
        return Metrics.cors(key, new MetricsResponse(Metrics.metricRegistry()));
    }

    /**
     * Binds the metrics history to the CORS aware URL {@code /metrics/metricsHistory} where the metrics access key is
     * provided in the form field {@code key} or an {@code Authorization: Jenkins-Metrics-Key {key}} header
     *
     * @param req the request
     * @param key the key from the form field.
     * @return the {@link HttpResponse}
     * @throws IllegalAccessException if the access attempt is invalid.
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public HttpResponse doMetricsHistory(StaplerRequest req, @QueryParameter("key") String key)
            throws IllegalAccessException {
        if (!Sampler.isEnabled()) {
            return HttpResponses.notFound();
        }
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyMetrics(key);
        return Metrics.cors(key, new MetricsHistoryResponse());
    }

    /**
     * Binds the ping check to the CORS aware URL {@code /metrics/ping} where the metrics access key is
     * provided in the form field {@code key} or an {@code Authorization: Jenkins-Metrics-Key {key}} header
     *
     * @param req the request
     * @param key the key from the form field.
     * @return the {@link HttpResponse}
     * @throws IllegalAccessException if the access attempt is invalid.
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    public HttpResponse doPing(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyPing(key);
        return Metrics.cors(key, new PingResponse());
    }

    /**
     * Binds the thread dump to the CORS aware URL {@code /metrics/threads} where the metrics access key is
     * provided in the form field {@code key} or an {@code Authorization: Jenkins-Metrics-Key {key}} header
     *
     * @param req the request
     * @param key the key from the form field.
     * @return the {@link HttpResponse}
     * @throws IllegalAccessException if the access attempt is invalid.
     */
    @SuppressWarnings("unused") // stapler binding
    @Restricted(NoExternalUse.class) // stapler binding
    @RequirePOST
    public HttpResponse doThreads(StaplerRequest req, @QueryParameter("key") String key) throws IllegalAccessException {
        requireCorrectMethod(req);
        if (StringUtils.isBlank(key)) {
            key = getKeyFromAuthorizationHeader(req);
        }
        Metrics.checkAccessKeyThreadDump(key);
        return Metrics.cors(key, new ThreadDumpResponse(new ThreadDump(ManagementFactory.getThreadMXBean())));
    }

    /**
     * A binding of the standard dropwizard metrics servlet into the stapler API
     */
    @Restricted(NoExternalUse.class) // only for use by stapler web binding
    public class Pseudoservlet {

        /**
         * Web binding for {@literal /healthcheck}
         *
         * @param req the request
         * @return the response
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        public HttpResponse doHealthcheck(StaplerRequest req) {
            long ifModifiedSince = req.getDateHeader("If-Modified-Since");
            long maxAge = getCacheControlMaxAge(req);
            Metrics.HealthCheckData data = Metrics.getHealthCheckData();
            if (data == null || (maxAge != -1 && data.getLastModified() + maxAge < System.currentTimeMillis())) {
                // if the max-age was specified, get live data
                data = new Metrics.HealthCheckData(Metrics.healthCheckRegistry().runHealthChecks());
            } else if (ifModifiedSince != -1 && data.getLastModified() < ifModifiedSince) {
                return HttpResponses.status(HttpServletResponse.SC_NOT_MODIFIED);
            }
            return new HealthCheckResponse(data);
        }

        /**
         * Web binding for {@literal /metrics}
         *
         * @return the response
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        public HttpResponse doMetrics() {
            return new MetricsResponse(Metrics.metricRegistry());
        }

        /**
         * Web binding for {@literal /metricsHistory}
         *
         * @return the response
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        public HttpResponse doMetricsHistory() {
            if (!Sampler.isEnabled()) {
                return HttpResponses.notFound();
            }
            return new MetricsHistoryResponse();
        }

        /**
         * Web binding for {@literal /ping}
         *
         * @return the response
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        public HttpResponse doPing() {
            return new PingResponse();
        }

        /**
         * Web binding for {@literal /threads}
         *
         * @return the response
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        public HttpResponse doThreads() {
            return new ThreadDumpResponse(new ThreadDump(ManagementFactory.getThreadMXBean()));
        }

        /**
         * Web binding for {@literal /}
         *
         * @return the response
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
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

    /**
     * A PING response.
     */
    private static class PingResponse implements HttpResponse {
        /**
         * The content of the response.
         */
        private static final String CONTENT = "pong";

        /**
         * {@inheritDoc}
         */
        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader(MetricsRootAction.CACHE_CONTROL, MetricsRootAction.NO_CACHE);
            resp.setContentType(PLAIN_TEXT_CONTENT_TYPE);
            final PrintWriter writer = resp.getWriter();
            try {
                writer.println(CONTENT);
            } finally {
                writer.close();
            }
        }
    }

    /**
     * A variant of {@link HttpResponses#status(int)} that supports the {@code Last-Modified} and {@code Expires}
     * headers.
     */
    private static class StatusResponse implements HttpResponse {

        /**
         * The status code.
         */
        private final int code;
        /**
         * The last modified.
         */
        private final long lastModified;
        /**
         * The expires time header to set (or {@code null})
         */
        @CheckForNull
        private final Long expires;

        public StatusResponse(int code, long lastModified, @CheckForNull Long expires) {
            this.code = code;
            this.lastModified = lastModified;
            this.expires = expires;
        }

        /**
         * {@inheritDoc}
         */
        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            resp.setStatus(code);
            if (expires == null) {
                resp.setHeader(CACHE_CONTROL, NO_CACHE);
            } else {
                resp.setHeader(CACHE_CONTROL, String.format(MAX_AGE,
                        TimeUnit.MILLISECONDS.toSeconds(expires - System.currentTimeMillis())));
                resp.setDateHeader("Expires", expires);
            }
            resp.setDateHeader("Last-Modified", lastModified);
        }
    }

    /**
     * A thead dump response.
     */
    private static class ThreadDumpResponse implements HttpResponse {
        /**
         * The thread dump.
         */
        private final ThreadDump threadDump;

        public ThreadDumpResponse(ThreadDump threadDump) {
            this.threadDump = threadDump;
        }

        /**
         * {@inheritDoc}
         */
        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader(CACHE_CONTROL, NO_CACHE);
            resp.setContentType(PLAIN_TEXT_CONTENT_TYPE);
            final OutputStream output = resp.getOutputStream();
            try {
                threadDump.dump(output);
            } finally {
                output.close();
            }
        }
    }

    /**
     * A health check response.
     */
    private class HealthCheckResponse implements HttpResponse {
        /**
         * The health check data.
         */
        @NonNull
        private final Metrics.HealthCheckData data;

        /**
         * Constructor.
         * @param data the data.
         */
        public HealthCheckResponse(@NonNull Metrics.HealthCheckData data) {
            this.data = data;
        }

        /**
         * {@inheritDoc}
         */
        public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                ServletException {
            boolean jsonp = StringUtils.isNotBlank(req.getParameter("callback"));
            String jsonpCallback = StringUtils.defaultIfBlank(req.getParameter("callback"), "callback");
            resp.setContentType(jsonp ? JSONP_CONTENT_TYPE : JSON_CONTENT_TYPE);
            Long expires = data.getExpires();
            if (expires == null) {
                resp.setHeader(CACHE_CONTROL, NO_CACHE);
            } else {
                resp.setHeader(CACHE_CONTROL, String.format(MAX_AGE,
                        TimeUnit.MILLISECONDS.toSeconds(expires - System.currentTimeMillis())));
                resp.setDateHeader("Expires", expires);
            }
            resp.setDateHeader("Last-Modified", data.getLastModified());
            SortedMap<String, HealthCheck.Result> results = data.getResults();
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

    /**
     * A metrics response.
     */
    private class MetricsResponse implements HttpResponse {
        /**
         * The registry to provide the response from.
         */
        private final MetricRegistry registry;

        /**
         * Constructor.
         * @param registry the registry.
         */
        private MetricsResponse(MetricRegistry registry) {
            this.registry = registry;
        }

        /**
         * {@inheritDoc}
         */
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

    /**
     * A metrics history response.
     */
    private class MetricsHistoryResponse implements HttpResponse {

        /**
         * {@inheritDoc}
         */
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
                Jenkins jenkins = Jenkins.getInstance();
                Sampler sampler = jenkins == null ? null : jenkins.getExtensionList(PeriodicWork.class).get(Sampler.class);
                Map<Date, Object> sample = sampler == null
                        ? null
                        : (prefix == null && postfix == null ? sampler.sample() : sampler.sample(prefix, postfix));
                output.write(getWriter(metricsMapper, req).writeValueAsBytes(sample));
                if (jsonp) {
                    output.write(");".getBytes("US-ASCII"));
                }
            } finally {
                output.close();
            }
        }
    }

    /**
     * Web binding for the current user.
     *
     * @see MetricsRootAction#getCurrentUser()
     */
    @Restricted(NoExternalUse.class) // only for use by stapler web binding
    public class CurrentUserPseudoservlet extends Pseudoservlet {
        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doHealthcheck(StaplerRequest req) {
            Jenkins.getInstance().checkPermission(Metrics.HEALTH_CHECK);
            return super.doHealthcheck(req);
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doIndex() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doIndex();
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doMetrics() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doMetrics();
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doMetricsHistory() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doMetricsHistory();
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doPing() {
            Jenkins.getInstance().checkPermission(Metrics.VIEW);
            return super.doPing();
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doThreads() {
            Jenkins.getInstance().checkPermission(Metrics.THREAD_DUMP);
            return super.doThreads();
        }
    }

    /**
     * Web binding for the access keys
     *
     * @see MetricsRootAction#getDynamic(String)
     */
    @Restricted(NoExternalUse.class) // only for use by stapler web binding
    public class AccessKeyPseudoservlet extends Pseudoservlet {
        /**
         * The access key for this binding.
         */
        private final String key;

        /**
         * Constructor.
         *
         * @param key the access key.
         */
        public AccessKeyPseudoservlet(String key) {
            this.key = key;
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doHealthcheck(StaplerRequest req) {
            Metrics.checkAccessKeyHealthCheck(key);
            return Metrics.cors(key, super.doHealthcheck(req));
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doMetrics() {
            Metrics.checkAccessKeyMetrics(key);
            return Metrics.cors(key, super.doMetrics());
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doMetricsHistory() {
            Metrics.checkAccessKeyMetrics(key);
            return Metrics.cors(key, super.doMetricsHistory());
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doPing() {
            Metrics.checkAccessKeyPing(key);
            return Metrics.cors(key, super.doPing());
        }

        /**
         * {@inheritDoc}
         */
        @Restricted(NoExternalUse.class) // only for use by stapler web binding
        @Override
        public HttpResponse doThreads() {
            Metrics.checkAccessKeyThreadDump(key);
            return Metrics.cors(key, super.doThreads());
        }
    }

    /**
     * Sampler that captures an exponential sample of metrics snapshots.
     */
    @Extension
    public static class Sampler extends PeriodicWork {

        /**
         * The field names that need rewriting of their immediate children.
         */
        private static final Set<String> METRIC_FIELD_NAMES = Collections.unmodifiableSet(
                new HashSet<String>(Arrays.asList("gauges", "counters", "histograms", "meters", "timers"))
        );
        /**
         * The size of the sampler, negative values will disable the sampler completely.
         */
        private static final int SIZE = Integer.getInteger(MetricsRootAction.class.getName()+".Sampler.SIZE", 128);
        /**
         * The bucket to retain history.
         */
        private final ExponentialLeakyBucket<Sample> bucket = new ExponentialLeakyBucket<Sample>(Math.max(1,SIZE), 0.005);
        /**
         * The {@link ObjectMapper} to use when sampling.
         */
        private final ObjectMapper mapper;
        /**
         * The best guess at the uncompressed JSON size.
         */
        private int averageSize = 8192;

        /**
         * Default constructor.
         */
        public Sampler() {
            mapper = new ObjectMapper();
            mapper.registerModule(new MetricsModule(RATE_UNIT, DURATION_UNIT, false));
        }

        /**
         * Rewrites a json node so that all its immediate fields have the prefix and postfix applied
         *
         * @param json    the object to rewrite
         * @param prefix  the prefix to apply
         * @param postfix the postfix to apply
         * @return the rewritten object
         */
        private static JsonNode renameFields(JsonNode json, String prefix, String postfix) {
            if ((prefix == null && postfix == null) || !json.isObject()) {
                return json;
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> fieldIterator = json.fields(); fieldIterator.hasNext(); ) {
                final Map.Entry<String, JsonNode> field = fieldIterator.next();
                result.set(name(prefix, field.getKey(), postfix), field.getValue());
            }
            return result;
        }

        private static JsonNode rewrite(JsonNode json, String prefix, String postfix) {
            if ((prefix == null && postfix == null) || !json.isObject()) {
                return json;
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> i = json.fields(); i.hasNext(); ) {
                final Map.Entry<String, JsonNode> entry = i.next();
                final String fieldName = entry.getKey();
                if (METRIC_FIELD_NAMES.contains(fieldName)) {
                    result.set(fieldName, renameFields(entry.getValue(), prefix, postfix));
                } else {
                    // pass-through
                    result.set(fieldName, entry.getValue());
                }

            }
            return result;
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

        public Map<Date, Object> sample(String prefix, String postfix) {
            Map<Date, Object> result = new TreeMap<Date, Object>();
            ObjectReader reader = mapper.reader(new JsonNodeFactory(false));
            for (Sample s : bucket.values()) {
                JsonNode value = s.getValue(reader);
                if (value != null) {
                    result.put(s.getTime(), rewrite(value, prefix, postfix));
                }
            }
            return result;
        }

        public static boolean isEnabled() {
            return SIZE <= 0;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long getRecurrencePeriod() {
            return isEnabled() ? TimeUnit.SECONDS.toMillis(30) : TimeUnit.DAYS.toMillis(1);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void doRun() throws Exception {
            if (isEnabled()) {
                ObjectWriter writer = mapper.writer();
                // always allocate 1kb more that the average compressed size
                ByteArrayOutputStream baos = new ByteArrayOutputStream(averageSize + 1024);
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
                byte[] compressedBytes = baos.toByteArray();
                // the compressed size should be an average of the recent values, this will give us
                // a computationally quick exponential move towards the average... we do not need a strict
                // exponential average
                averageSize = Math.max(8192, (7 * averageSize + compressedBytes.length) / 8);
                bucket.add(new Sample(System.currentTimeMillis(), compressedBytes));
            }
        }

        /**
         * A sample.
         */
        @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "EI_EXPOSE_REP2")
        public static class Sample {
            /**
             * The time when the sample was captured.
             */
            private final long t;
            /**
             * The GZip compressed JSON of the sample.
             */
            @NonNull
            private final byte[] v;

            /**
             * Constructor.
             * @param t the time of the sample.
             * @param v the compressed JSON bytes.
             */
            public Sample(long t, @NonNull byte[] v) {
                this.t = t;
                this.v = v;
            }

            /**
             * Gets the time the sample was taken.
             * @return the time the sample was taken.
             */
            @NonNull
            public Date getTime() {
                return new Date(t);
            }

            /**
             * Gets the JSON from the sample.
             * @param reader the {@link ObjectReader} to use.
             * @return the JSON.
             */
            @CheckForNull
            public JsonNode getValue(@NonNull ObjectReader reader) {
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
