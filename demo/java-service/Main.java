import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.logging.Logger;

public class Main {
    private static final Logger log = Logger.getLogger(Main.class.getName());
    private static Tracer tracer;
    private static LongCounter reqCounter;
    private static OpenTelemetry openTelemetry;
    private static final Random random = new Random();
    private static final W3CTraceContextPropagator propagator =
        W3CTraceContextPropagator.getInstance();

    // ── 从 HTTP Headers 提取 W3C traceparent ──────────
    private static final TextMapGetter<Headers> HEADER_GETTER = new TextMapGetter<Headers>() {
        @Override
        public Iterable<String> keys(Headers headers) {
            return headers.keySet();
        }

        @Override
        public String get(Headers headers, String key) {
            if (headers == null) return null;
            return headers.getFirst(key);
        }
    };

    // ── 向 HTTP Headers 注入 W3C traceparent ──────────
    private static final TextMapSetter<HttpRequest.Builder> HEADER_SETTER =
        (builder, key, value) -> builder.header(key, value);

    public static void main(String[] args) throws Exception {
        String svcName = "java-service";
        String otlpEndpoint = System.getenv().getOrDefault(
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://tempo-distributor.monitoring:4317");

        // ── TracerProvider (OTLP → Tempo) ──────────────
        OtlpGrpcSpanExporter traceExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .build();

        Resource resource = Resource.getDefault().merge(
            Resource.create(io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("service.name"), svcName,
                io.opentelemetry.api.common.AttributeKey.stringKey("service.namespace"), "demo"
            ))
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(traceExporter).build())
            .setSampler(Sampler.alwaysOn())
            .build();

        // ── MeterProvider (Prometheus) ──────────────────
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PrometheusHttpServer.builder().setPort(9090).build())
            .build();

        openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setPropagators(ContextPropagators.create(propagator))
            .buildAndRegisterGlobal();

        tracer = openTelemetry.getTracer(svcName);
        Meter meter = openTelemetry.getMeter(svcName);
        reqCounter = meter.counterBuilder("http_requests_total")
            .setDescription("total http requests")
            .build();

        // ── HTTP Server ─────────────────────────────────
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/hello", new HelloHandler());
        server.createContext("/api/chain", new ChainHandler());
        server.setExecutor(null);

        logWithSpan(null, null, svcName, "Java Service starting on :" + port);
        server.start();
    }

    static class HelloHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");

            // ── 提取上游传入的 W3C trace context ──────
            Context parentCtx = propagator.extract(
                Context.current(), exchange.getRequestHeaders(), HEADER_GETTER);

            Span span = tracer.spanBuilder("GET /api/hello")
                .setParent(parentCtx)
                .startSpan();
            try (Scope scope = span.makeCurrent()) {
                SpanContext sc = span.getSpanContext();
                String traceId = sc.getTraceId();
                String spanId = sc.getSpanId();

                reqCounter.add(1, Attributes.builder()
                    .put("handler", "hello").build());

                // ── Trace Label (Span Attribute) ──────
                span.setAttribute("business.operation", "hello");
                span.setAttribute("business.priority", 1L);
                span.setAttribute("user.role", "anonymous");

                // 模拟处理
                Thread.sleep(20 + random.nextInt(80));

                // 调用 Python 服务 (注入 traceparent)
                String pythonURL = System.getenv().getOrDefault(
                    "PYTHON_SERVICE_URL", "http://python-service.demo:8080");
                String result = "Java says hello!";

                try {
                    HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(pythonURL + "/api/hello"))
                        .timeout(Duration.ofSeconds(5))
                        .GET();

                    // ── 注入 W3C Trace Context 到下游请求 ──
                    propagator.inject(Context.current(), reqBuilder, HEADER_SETTER);

                    HttpRequest req = reqBuilder.build();
                    HttpResponse<String> resp = client.send(req,
                        HttpResponse.BodyHandlers.ofString());
                    result += " → " + resp.body();
                } catch (Exception e) {
                    result += " → (Python unreachable: " + e.getMessage() + ")";
                    span.setAttribute("error", e.getMessage());
                }

                logWithSpan(span, traceId, "java-service",
                    String.format("span_id=%s result=%s", spanId, result));
                byte[] respBytes = result.getBytes();
                exchange.sendResponseHeaders(200, respBytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(respBytes);
                os.close();
            } catch (InterruptedException e) {
                exchange.sendResponseHeaders(500, 0);
            } finally {
                span.end();
            }
        }
    }

    static class ChainHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");

            // ── 提取上游传入的 W3C trace context ──────
            Context parentCtx = propagator.extract(
                Context.current(), exchange.getRequestHeaders(), HEADER_GETTER);

            Span span = tracer.spanBuilder("GET /api/chain")
                .setParent(parentCtx)
                .startSpan();
            try (Scope scope = span.makeCurrent()) {
                SpanContext sc = span.getSpanContext();
                String traceId = sc.getTraceId();

                reqCounter.add(1, Attributes.builder()
                    .put("handler", "chain").build());

                // ── Trace Label (Span Attribute) ──────
                span.setAttribute("business.operation", "chain");
                span.setAttribute("business.priority", 2L);

                // 模拟处理
                Thread.sleep(10 + random.nextInt(30));
                span.addEvent("java-processing",
                    Attributes.builder().put("step", "java-transform").build());

                // 调用 Python 服务 (注入 traceparent)
                String pythonURL = System.getenv().getOrDefault(
                    "PYTHON_SERVICE_URL", "http://python-service.demo:8080");
                String chain = "Java → ";

                try {
                    HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build();
                    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(pythonURL + "/api/chain"))
                        .timeout(Duration.ofSeconds(5)).GET();

                    // ── 注入 W3C Trace Context ──────────
                    propagator.inject(Context.current(), reqBuilder, HEADER_SETTER);

                    HttpRequest req = reqBuilder.build();
                    HttpResponse<String> resp = client.send(req,
                        HttpResponse.BodyHandlers.ofString());
                    chain += resp.body();
                } catch (Exception e) {
                    chain += "(error: " + e.getMessage() + ")";
                    span.setAttribute("error", e.getMessage());
                }

                logWithSpan(span, traceId, "java-service",
                    String.format("chain=%s", chain));
                byte[] respBytes = chain.getBytes();
                exchange.sendResponseHeaders(200, respBytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(respBytes);
                os.close();
            } catch (InterruptedException e) {
                exchange.sendResponseHeaders(500, 0);
            } finally {
                span.end();
            }
        }
    }

    // ── 关键: trace_id 写入日志 → Loki 解析 → Grafana 联动 ──
    static void logWithSpan(Span span, String traceId, String svc, String msg) {
        if (span != null && traceId != null) {
            log.info(String.format("[%s] trace_id=%s %s", svc, traceId, msg));
        } else {
            log.info(String.format("[%s] %s", svc, msg));
        }
    }
}