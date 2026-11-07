"""Python Demo Service — OpenTelemetry + Prometheus metrics + structured logging

依赖:
  pip install opentelemetry-api opentelemetry-sdk \
              opentelemetry-exporter-otlp-proto-grpc \
              opentelemetry-exporter-prometheus \
              opentelemetry-instrumentation-flask \
              opentelemetry-instrumentation-requests \
              flask requests
"""
import os
import random
import time
import logging
import sys

from flask import Flask, request
import requests

from opentelemetry import trace, metrics, propagate
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.resources import Resource, SERVICE_NAME, SERVICE_NAMESPACE
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.exporter.prometheus import PrometheusMetricReader
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.trace.sampling import ALWAYS_ON
from opentelemetry.instrumentation.flask import FlaskInstrumentor
from opentelemetry.instrumentation.requests import RequestsInstrumentor

# ── 日志: 结构化格式, 带 trace_id / span_id ──────────────
logging.basicConfig(
    stream=sys.stdout,
    format='[python-service] %(message)s',
    level=logging.INFO,
)
log = logging.getLogger(__name__)

# ── OpenTelemetry 初始化 ─────────────────────────────────
SVC_NAME = "python-service"
otlp_endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT", "tempo-distributor.monitoring:4317")

# TracerProvider
resource = Resource.create({
    SERVICE_NAME: SVC_NAME,
    SERVICE_NAMESPACE: "demo",
})

trace_exporter = OTLPSpanExporter(endpoint=otlp_endpoint, insecure=True)
tracer_provider = TracerProvider(
    resource=resource,
    sampler=ALWAYS_ON,
    active_span_processor=BatchSpanProcessor(trace_exporter),
)
trace.set_tracer_provider(tracer_provider)
tracer = trace.get_tracer(SVC_NAME)

# MeterProvider (Prometheus 在 /metrics)
prom_reader = PrometheusMetricReader()
meter_provider = MeterProvider(resource=resource, metric_readers=[prom_reader])
metrics.set_meter_provider(meter_provider)
meter = metrics.get_meter(SVC_NAME)

req_counter = meter.create_counter(
    "http_requests_total",
    description="total http requests",
)

# ── Flask ────────────────────────────────────────────────
app = Flask(__name__)

# FlaskInstrumentor 自动提取入站 W3C trace context
FlaskInstrumentor().instrument_app(app)

# RequestsInstrumentor 自动向出站 requests 调用注入 traceparent
RequestsInstrumentor().instrument()


@app.route("/metrics")
def metrics_endpoint():
    """Prometheus scrape endpoint"""
    from prometheus_client import generate_latest, REGISTRY
    return generate_latest(REGISTRY), 200, {"Content-Type": "text/plain"}


@app.route("/api/hello")
def hello():
    span = trace.get_current_span()
    sc = span.get_span_context()
    trace_id = format(sc.trace_id, "032x")
    span_id = format(sc.span_id, "016x")

    req_counter.add(1, {"handler": "hello"})

    # ── Trace Label (Span Attribute) 示例 ─────────────
    span.set_attributes({
        "business.operation": "hello",
        "business.priority": 1,
        "user.role": "anonymous",
    })

    # 模拟业务
    time.sleep(random.uniform(0.02, 0.08))

    result = f"Python says hello! (depth=3, leaf)"
    log.info(f"trace_id={trace_id} span_id={span_id} result={result}")
    return result


@app.route("/api/chain")
def chain():
    span = trace.get_current_span()
    sc = span.get_span_context()
    trace_id = format(sc.trace_id, "032x")
    span_id = format(sc.span_id, "016x")

    req_counter.add(1, {"handler": "chain"})

    # ── Trace Label (Span Attribute) ──────────────────
    span.set_attributes({
        "business.operation": "chain",
        "business.priority": 2,
    })

    # 模拟处理
    time.sleep(random.uniform(0.01, 0.04))
    span.add_event("python-final", {"step": "python-format"})

    chain = "Python"
    log.info(f"trace_id={trace_id} span_id={span_id} chain={chain}")
    return chain


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    log.info(f"Python Service starting on :{port}")
    app.run(host="0.0.0.0", port=port, debug=False)