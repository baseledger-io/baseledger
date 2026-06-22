package features.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import io.opentelemetry.instrumentation.runtimemetrics.java8.*

object Observability:

  final case class Handle(otel: OpenTelemetry, sdk: OpenTelemetrySdk):
    def shutdown(): Unit =
      sdk.close()

  def init(serviceName: String): Handle =
    val sdk = AutoConfiguredOpenTelemetrySdk
      .builder()
      .addPropertiesSupplier(() => java.util.Map.of("otel.service.name", serviceName))
      .build()
      .getOpenTelemetrySdk

    // Register JVM runtime metrics
    Classes.registerObservers(sdk)
    Cpu.registerObservers(sdk)
    GarbageCollector.registerObservers(sdk)
    MemoryPools.registerObservers(sdk)
    Threads.registerObservers(sdk)

    Handle(sdk, sdk)
