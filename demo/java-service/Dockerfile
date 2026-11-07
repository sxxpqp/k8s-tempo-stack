FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# 下载 OpenTelemetry jars
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-api/1.38.0/opentelemetry-api-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-api-incubator/1.38.0-alpha/opentelemetry-api-incubator-1.38.0-alpha.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk/1.38.0/opentelemetry-sdk-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk-common/1.38.0/opentelemetry-sdk-common-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk-trace/1.38.0/opentelemetry-sdk-trace-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk-metrics/1.38.0/opentelemetry-sdk-metrics-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk-logs/1.38.0/opentelemetry-sdk-logs-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-exporter-otlp/1.38.0/opentelemetry-exporter-otlp-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-exporter-otlp-common/1.38.0/opentelemetry-exporter-otlp-common-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-exporter-common/1.38.0/opentelemetry-exporter-common-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk-extension-autoconfigure/1.38.0/opentelemetry-sdk-extension-autoconfigure-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-sdk-extension-autoconfigure-spi/1.38.0/opentelemetry-sdk-extension-autoconfigure-spi-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-exporter-prometheus/1.38.0-alpha/opentelemetry-exporter-prometheus-1.38.0-alpha.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-exporter-sender-okhttp/1.38.0/opentelemetry-exporter-sender-okhttp-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/opentelemetry/opentelemetry-context/1.38.0/opentelemetry-context-1.38.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/com/squareup/okio/okio/3.6.0/okio-3.6.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib/1.9.10/kotlin-stdlib-1.9.10.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.9.10/kotlin-stdlib-jdk8-1.9.10.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/org/jetbrains/kotlin/kotlin-stdlib-common/1.9.10/kotlin-stdlib-common-1.9.10.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-core/1.2.1/prometheus-metrics-core-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-model/1.2.1/prometheus-metrics-model-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-config/1.2.1/prometheus-metrics-config-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-exporter-common/1.2.1/prometheus-metrics-exporter-common-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-exporter-httpserver/1.2.1/prometheus-metrics-exporter-httpserver-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-tracer-common/1.2.1/prometheus-metrics-tracer-common-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-exposition-formats/1.2.1/prometheus-metrics-exposition-formats-1.2.1.jar /app/lib/
ADD https://maven.aliyun.com/repository/public/io/prometheus/prometheus-metrics-shaded-protobuf/1.2.1/prometheus-metrics-shaded-protobuf-1.2.1.jar /app/lib/

COPY Main.java .
RUN javac -cp "lib/*" Main.java

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/lib /app/lib
COPY --from=builder /app/*.class /app/
EXPOSE 8080 9090
ENTRYPOINT ["java", "-cp", ".:lib/*", "Main"]