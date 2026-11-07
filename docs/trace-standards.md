# OpenTelemetry Trace 生产规范

> 版本: v1.0 | 适用: Go / Java / Python | 基于 W3C Trace Context

---

## 1. 传播协议

进出站统一使用 **W3C `traceparent` header**，不跨协议混用。

```go
// Go
otel.SetTextMapPropagator(propagation.TraceContext{})
```

```java
// Java
W3CTraceContextPropagator propagator = W3CTraceContextPropagator.getInstance();
openTelemetry = OpenTelemetrySdk.builder()
    .setPropagators(ContextPropagators.create(propagator))
    .build();
```

```python
# Python: FlaskInstrumentor / RequestsInstrumentor 自动处理
```

---

## 2. Resource 属性（必须包含）

| 属性 | 示例 | 必须 |
|------|------|:--:|
| `service.name` | `order-service` | ✅ |
| `service.namespace` | `production` | ✅ |
| `service.version` | `v1.2.3` | ✅ |
| `service.instance.id` | `pod-name / hostname` | ✅ |
| `deployment.environment` | `prod / staging / dev` | 推荐 |

---

## 3. Span 命名规范

**原则：低基数，不含动态值。**

| 操作类型 | 正确 | 错误 |
|----------|------|------|
| HTTP Server | `GET /api/orders` | `GET /api/orders/12345` |
| HTTP Client | `HTTP GET http://payment-svc:8080` | — |
| Database | `SELECT users` | `SELECT * FROM users WHERE id=123` |
| Message Queue | `publish order.created` | — |

---

## 4. Span Kind（必须设置）

```go
// Go: otelhttp 自动设 SERVER / CLIENT
```

```java
// Java: 手动 Span 必须显式设置
Span span = tracer.spanBuilder("GET /api/hello")
    .setParent(parentCtx)
    .setSpanKind(SpanKind.SERVER)  // ← 必须
    .startSpan();
```

| Kind | 场景 |
|------|------|
| `SERVER` | 接收外部请求 |
| `CLIENT` | 发起外部调用 |
| `INTERNAL` | 内部异步操作 |
| `PRODUCER` | 发送消息 |
| `CONSUMER` | 消费消息 |

---

## 5. Span Status（必须设置）

```go
// Go
import "go.opentelemetry.io/otel/codes"

if err != nil {
    span.SetStatus(codes.Error, err.Error())
}
```

```java
// Java
import io.opentelemetry.api.trace.StatusCode;

} catch (Exception e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
}
```

```python
# Python
from opentelemetry.sdk.trace import StatusCode

try:
    ...
except Exception as e:
    span.set_status(StatusCode.ERROR, str(e))
    span.record_exception(e)
```

---

## 6. OTel 语义约定 — Span Attribute 标准规范

> 以下为 OpenTelemetry 官方语义约定 (Semantic Conventions v1.26+)，**SDK 自动注入**，无需手动设置。
> 在生产环境可按标准 key 做 Trace 搜索、过滤、告警。完整规范: [opentelemetry.io/docs/specs/semconv](https://opentelemetry.io/docs/specs/semconv/)

### 6.1 HTTP Server Span（入站请求）

`SpanKind: SERVER` — 由 `otelhttp` / `FlaskInstrumentor` / `Servlet` 自动注入。

| Attribute | 类型 | 示例 | 必须 |
|-----------|------|------|:--:|
| `http.method` | string | `GET` | ✅ |
| `http.status_code` | int | `200` | ✅ |
| `http.route` | string | `/api/orders/{id}` | ✅ |
| `url.scheme` | string | `http` | ✅ |
| `url.path` | string | `/api/orders` | ✅ |
| `network.protocol.version` | string | `1.1` | ✅ |
| `server.address` | string | `order-service` | 推荐 |
| `server.port` | int | `8080` | 推荐 |
| `user_agent.original` | string | `curl/8.0` | 推荐 |
| `client.address` | string | `10.1.2.3` | 推荐 |

### 6.2 HTTP Client Span（出站请求）

`SpanKind: CLIENT` — 由 `otelhttp.Transport` / `RequestsInstrumentor` / `HttpClient` 自动注入。

| Attribute | 类型 | 示例 | 必须 |
|-----------|------|------|:--:|
| `http.method` | string | `GET` | ✅ |
| `http.status_code` | int | `200` | ✅ |
| `url.full` | string | `http://java-service:8080/api/hello` | ✅ |
| `server.address` | string | `java-service` | ✅ |
| `server.port` | int | `8080` | ✅ |

### 6.3 Database Span

由 `otelsql` / `Jedis` / `psycopg` 等 instrumentation 自动注入。

| Attribute | 类型 | 示例 | 必须 |
|-----------|------|------|:--:|
| `db.system` | string | `mysql` `postgresql` `redis` | ✅ |
| `db.name` | string | `orders_db` | ✅ |
| `db.operation` | string | `SELECT` `HGET` | ✅ |
| `db.statement` | string | `SELECT * FROM orders` | 推荐 |
| `server.address` | string | `mysql-primary` | ✅ |
| `server.port` | int | `3306` | ✅ |

### 6.4 RPC / gRPC Span

| Attribute | 类型 | 示例 | 必须 |
|-----------|------|------|:--:|
| `rpc.system` | string | `grpc` | ✅ |
| `rpc.service` | string | `OrderService` | ✅ |
| `rpc.method` | string | `CreateOrder` | ✅ |
| `rpc.grpc.status_code` | int | `0` | ✅ |

### 6.5 Messaging Span（RabbitMQ / Kafka）

| Attribute | 类型 | 示例 |
|-----------|------|------|
| `messaging.system` | string | `kafka` `rabbitmq` |
| `messaging.destination` | string | `order.created` |
| `messaging.operation` | string | `publish` `receive` |
| `messaging.message.id` | string | `msg-12345` |

### 6.6 通用 Span Attribute

| Attribute | 说明 |
|-----------|------|
| `service.name` | 服务名（Resource 层，Tempo 按此建索引） |
| `service.namespace` | 命名空间 |
| `telemetry.sdk.name` | `opentelemetry` |
| `telemetry.sdk.language` | `go` `java` `python` |
| `telemetry.sdk.version` | `1.30.0` |

### 6.7 业务自定义属性

业务自定义属性遵循 `snake_case` + 前缀命名，避免与 OTel 标准 key 冲突。

```go
span.SetAttributes(
    attribute.String("business.operation", "checkout"),
    attribute.String("order.id", "ORD-67890"),
    attribute.Int("business.priority", 1),
    // ⚠️ 高基数: user.id 仅用于搜索，不作为 metric label
    attribute.String("user.id", "u-12345"),
)
```

```python
span.set_attributes({
    "business.operation": "hello",
    "business.priority": 1,
    "user.role": "anonymous",
})
```

### 6.8 Attribute 规则总结

| 规则 | 说明 |
|------|------|
| 命名 | `snake_case` 小写下划线 |
| 前缀 | 标准域: `http.` `db.` `rpc.` `messaging.` |
| 类型 | `string` / `int` / `float` / `bool` |
| 数量上限 | 每 Span ≤ 128 个 attribute |
| 基数控制 | `user.id` 可搜索但**不作为 metric label**（避免时序膨胀） |
| 值长度 | 单个值 ≤ 256 字符 |

---

## 7. Span Event（关键步骤标记）

用于标记缓存命中/未命中、重试、降级等关键节点。

```go
span.AddEvent("cache.miss", trace.WithAttributes(
    attribute.String("key", "user:12345"),
))
```

```java
span.addEvent("java-processing",
    Attributes.builder().put("step", "java-transform").build());
```

```python
span.add_event("python-final", {"step": "python-format"})
```

---

## 8. 异常记录

```go
span.RecordError(err)
span.SetAttributes(attribute.String("error", err.Error()))
span.SetStatus(codes.Error, err.Error())
```

```python
span.record_exception(e)
span.set_status(StatusCode.ERROR, str(e))
```

---

## 9. 采样策略

| 环境 | 策略 | 说明 |
|------|------|------|
| **生产** | `ParentBased(0.1)` | 10% 采样，子 Span 跟随父 Span |
| **关键链路** | `AlwaysSample()` | 支付 / 认证等核心路径 |
| **调试** | `AlwaysSample()` | 临时开启 |

```go
// 生产
sdktrace.WithSampler(sdktrace.ParentBased(
    sdktrace.TraceIDRatioBased(0.1),
))
```

```java
// 生产
Sampler.parentBased(Sampler.traceIdRatioBased(0.1))
```

> ⚠️ `AlwaysSample()` 在生产环境会导致海量 Trace 数据，存储和网络成本剧增。

---

## 10. 日志关联

每行日志必须包含 `trace_id` + `span_id`，Loki 通过正则提取实现 Log ↔ Trace 联动。

```
[go-service] trace_id=abc123def456 span_id=7890abcd request completed
```

```go
func logWithTrace(ctx context.Context, msg string) {
    span := trace.SpanFromContext(ctx)
    log.Printf("trace_id=%s span_id=%s %s",
        span.SpanContext().TraceID().String(),
        span.SpanContext().SpanID().String(),
        msg,
    )
}
```

Loki 数据源配置：
```yaml
derivedFields:
  - datasourceUid: tempo
    matcherRegex: 'trace_id[=:"\s]+(\w+)'
    name: traceID
```

---

## 11. 安全红线

| ❌ 禁止 | ✅ 允许 |
|---------|--------|
| `password`, `token`, `secret` | `user.id`, `tenant.id` |
| `credit_card`, `ssn`, PII | `order.id`, `product.id` |
| `request.body` (含敏感字段) | `http.method`, `http.status_code` |
| `Authorization` header 值 | `service.name`, `service.version` |

---

## 12. Demo → 生产改造清单

| 项目 | Demo 现状 | 生产要求 |
|------|-----------|----------|
| 采样率 | `AlwaysSample` 100% | `ParentBased(0.1)` 10% |
| Span Kind | Go/Python 自动 ✅ / Java 手动 ✅ | 必须设置 |
| Span Status | ✅ 已补全 | 必须设置 |
| Resource | `service.name` + `namespace` | + `version` + `instance.id` |
| 日志关联 | `trace_id` 已在日志中 | ✅ 达标 |
| 异常处理 | ✅ 已补全 | `RecordException` + `Status.Error` |

---

## 参考资料

- [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [Grafana Tempo Exemplar](https://grafana.com/docs/grafana/latest/datasources/tempo/#exemplars)