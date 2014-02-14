package com.codahale.metrics.jenkins;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.json.HealthCheckModule;
import com.codahale.metrics.json.MetricsModule;
import com.codahale.metrics.jvm.ThreadDump;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

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

    public class Admin {

        private boolean isAllHealthy(Map<String, HealthCheck.Result> results) {
            for (HealthCheck.Result result : results.values()) {
                if (!result.isHealthy()) {
                    return false;
                }
            }
            return true;
        }

        private ObjectWriter getWriter(ObjectMapper mapper, HttpServletRequest request) {
            final boolean prettyPrint = Boolean.parseBoolean(request.getParameter("pretty"));
            if (prettyPrint) {
                return mapper.writerWithDefaultPrettyPrinter();
            }
            return mapper.writer();
        }

        public HttpResponse doHealthcheck() {
            final SortedMap<String, HealthCheck.Result> results = Metrics.healthCheckRegistry().runHealthChecks();
            return new HttpResponse() {
                private static final String CONTENT_TYPE = "application/json";
                private static final String CACHE_CONTROL = "Cache-Control";
                private static final String NO_CACHE = "must-revalidate,no-cache,no-store";

                public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                        ServletException {
                    resp.setHeader(CACHE_CONTROL, NO_CACHE);
                    resp.setContentType(CONTENT_TYPE);
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
                        getWriter(healthCheckMapper, req).writeValue(output, results);
                    } finally {
                        output.close();
                    }
                }
            };
        }

        public HttpResponse doMetrics() {
            return new HttpResponse() {
                private static final String CONTENT_TYPE = "application/json";
                private static final String CACHE_CONTROL = "Cache-Control";
                private static final String NO_CACHE = "must-revalidate,no-cache,no-store";

                public void generateResponse(StaplerRequest req, StaplerResponse resp, Object node) throws IOException,
                        ServletException {
                    resp.setStatus(HttpServletResponse.SC_OK);
                    resp.setHeader(CACHE_CONTROL, NO_CACHE);
                    resp.setContentType(CONTENT_TYPE);

                    final OutputStream output = resp.getOutputStream();
                    try {
                        getWriter(healthCheckMapper, req).writeValue(output, Metrics.metricRegistry());
                    } finally {
                        output.close();
                    }
                }
            };
        }

        public HttpResponse doPing() {
            return new HttpResponse() {
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
            };
        }

        public HttpResponse doThreads() {
            final ThreadDump threadDump = new ThreadDump(ManagementFactory.getThreadMXBean());
            return new HttpResponse() {
                private static final String CONTENT_TYPE = "text/plain";
                private static final String CACHE_CONTROL = "Cache-Control";
                private static final String NO_CACHE = "must-revalidate,no-cache,no-store";

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
            };
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

}
