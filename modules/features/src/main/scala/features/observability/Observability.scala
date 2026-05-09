package features.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes

/**
 * Builds the application's [[OpenTelemetry]] instance and exposes it
 * for Tapir's metrics interceptor and any custom gauges.
 *
 * ==Why==
 * For v1 we want a single, vendor-agnostic place that produces an
 * `OpenTelemetry` SDK with a Prometheus scrape endpoint and OTLP tracing.
 * The Prometheus exporter starts its own embedded HTTP server (default
 * port 9464) so our main HTTP server stays unaffected by /metrics traffic.
 *
 * ==When==
 * Constructed once at startup from `RootGuardian` and passed into
 * `configureHttp` and the dispatcher gauge registration.
 *
 * ==How to extend==
 * To add OTLP push exporters, register additional `MetricReader`s on
 * the `SdkMeterProvider` builder. Always close via [[Observability#shutdown]]
 * at `CoordinatedShutdown` time to flush pending data and free the port.
 */
object Observability:

  final case class Handle(otel: OpenTelemetry, prometheus: PrometheusHttpServer):
    def shutdown(): Unit =
      prometheus.close()

  def init(serviceName: String, prometheusPort: Int): Handle =
    val resource = Resource.getDefault.merge(
      Resource.create(io.opentelemetry.api.common.Attributes.of(ServiceAttributes.SERVICE_NAME, serviceName))
    )

    val prometheusReader = PrometheusHttpServer.builder().setPort(prometheusPort).build()

    val meterProvider = SdkMeterProvider
      .builder()
      .setResource(resource)
      .registerMetricReader(prometheusReader)
      .build()

    val spanExporter = OtlpGrpcSpanExporter.builder().build()
    val tracerProvider = SdkTracerProvider
      .builder()
      .setResource(resource)
      .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
      .build()

    val otel = OpenTelemetrySdk
      .builder()
      .setMeterProvider(meterProvider)
      .setTracerProvider(tracerProvider)
      .build()

    Handle(otel, prometheusReader)
