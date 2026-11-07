package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net/http"
	"os"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	otelprom "go.opentelemetry.io/otel/exporters/prometheus"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
	"go.opentelemetry.io/otel/trace"
)

var (
	tracer     trace.Tracer
	meter      metric.Meter
	reqCounter metric.Int64Counter
)

func main() {
	svcName := "go-service"
	otlpEndpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if otlpEndpoint == "" {
		otlpEndpoint = "tempo-distributor.monitoring:4318"
	}

	// ── TracerProvider (OTLP → Tempo) ──────────────────
	ctx := context.Background()
	traceExporter, err := otlptracehttp.New(ctx,
		otlptracehttp.WithEndpoint(otlpEndpoint),
		otlptracehttp.WithInsecure(),
	)
	if err != nil {
		log.Fatalf("failed to create trace exporter: %v", err)
	}

	res := resource.NewWithAttributes(
		semconv.SchemaURL,
		semconv.ServiceName(svcName),
		semconv.ServiceNamespace("demo"),
	)

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(traceExporter),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.AlwaysSample()),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	tracer = tp.Tracer(svcName)

	// ── MeterProvider (Prometheus) ──────────────────────
	promExporter, err := otelprom.New(
		otelprom.WithRegisterer(prometheus.DefaultRegisterer),
	)
	if err != nil {
		log.Fatalf("failed to create prometheus exporter: %v", err)
	}
	mp := sdkmetric.NewMeterProvider(sdkmetric.WithReader(promExporter))
	otel.SetMeterProvider(mp)
	meter = mp.Meter(svcName)

	reqCounter, err = meter.Int64Counter("http_requests_total",
		metric.WithDescription("total http requests"),
	)
	if err != nil {
		log.Fatalf("failed to create counter: %v", err)
	}

	// ── HTTP Server ─────────────────────────────────────
	mux := http.NewServeMux()

	// /metrics for Prometheus — 使用 prometheus/client_golang 原生 handler
	mux.Handle("/metrics", promhttp.Handler())

	// /api/hello → 调用 Java 服务
	mux.Handle("/api/hello", otelhttp.NewHandler(
		http.HandlerFunc(goHelloHandler), "GET /api/hello",
	))

	// /api/chain → 链式调用 Go → Java → Python
	mux.Handle("/api/chain", otelhttp.NewHandler(
		http.HandlerFunc(chainHandler), "GET /api/chain",
	))

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	logWithTrace(nil, svcName, "Go Service starting on :"+port)
	http.ListenAndServe(":"+port, mux)
}

func goHelloHandler(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	span := trace.SpanFromContext(ctx)
	traceID := span.SpanContext().TraceID().String()
	spanID := span.SpanContext().SpanID().String()

	reqCounter.Add(ctx, 1,
		metric.WithAttributes(attribute.String("handler", "hello")),
	)

	// ── Trace Label (Span Attribute) 示例 ──────────
	// 通过 SetAttributes 给当前 Span 打上业务标签,
	// 在 Tempo / Grafana 中可按这些标签搜索和过滤 Trace
	span.SetAttributes(
		attribute.String("business.operation", "hello"),
		attribute.Int("business.priority", 1),
		attribute.String("user.role", "anonymous"),
	)

	// 模拟业务逻辑
	time.Sleep(time.Duration(20+rand.Intn(80)) * time.Millisecond)

	// 调用 Java 服务
	javaURL := os.Getenv("JAVA_SERVICE_URL")
	if javaURL == "" {
		javaURL = "http://java-service.demo:8080"
	}

	client := http.Client{Transport: otelhttp.NewTransport(http.DefaultTransport)}
	req, _ := http.NewRequestWithContext(ctx, "GET", javaURL+"/api/hello", nil)
	resp, err := client.Do(req)

	result := "Go says hello!"
	if err == nil {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		result += " → " + string(body)
	} else {
		result += " → (Java unreachable: " + err.Error() + ")"
		span.SetAttributes(attribute.String("error", err.Error()))
	}

	logWithTrace(&ctx, "go-service", fmt.Sprintf("trace_id=%s span_id=%s handled request, result: %s",
		traceID, spanID, result))
	w.Write([]byte(result))
}

func chainHandler(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	span := trace.SpanFromContext(ctx)
	traceID := span.SpanContext().TraceID().String()

	reqCounter.Add(ctx, 1,
		metric.WithAttributes(attribute.String("handler", "chain")),
	)

	// ── Trace Label (Span Attribute) 示例 ──────────
	span.SetAttributes(
		attribute.String("business.operation", "chain"),
		attribute.Int("business.priority", 2),
	)

	// Step 1: Go 自身操作
	span.AddEvent("go-processing", trace.WithAttributes(
		attribute.String("step", "go-crunch"),
	))
	time.Sleep(time.Duration(10+rand.Intn(40)) * time.Millisecond)

	// Step 2: 调用 Java 服务 (otelhttp 自动传播 traceparent)
	javaURL := os.Getenv("JAVA_SERVICE_URL")
	if javaURL == "" {
		javaURL = "http://java-service.demo:8080"
	}

	client := http.Client{Transport: otelhttp.NewTransport(http.DefaultTransport)}
	req, _ := http.NewRequestWithContext(ctx, "GET", javaURL+"/api/chain", nil)
	resp, err := client.Do(req)

	chain := "Go → "
	if err == nil {
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		chain += string(body)
	} else {
		chain += fmt.Sprintf("(error: %v)", err)
		span.SetAttributes(attribute.String("error", err.Error()))
	}

	logWithTrace(&ctx, "go-service",
		fmt.Sprintf("trace_id=%s chain=%s", traceID, chain))
	w.Write([]byte(chain))
}

// ── 关键: 把 trace_id / span_id 写入日志 ──────────────────
// Loki 通过 derivedFields 正则 trace_id=(\w+) 提取后,
// Grafana 中即可一键从日志跳转到对应的 Tempo Trace
func logWithTrace(ctx *context.Context, svc, msg string) {
	if ctx != nil {
		span := trace.SpanFromContext(*ctx)
		tid := span.SpanContext().TraceID().String()
		sid := span.SpanContext().SpanID().String()
		log.Printf("[%s] trace_id=%s span_id=%s %s", svc, tid, sid, msg)
	} else {
		log.Printf("[%s] %s", svc, msg)
	}
}
