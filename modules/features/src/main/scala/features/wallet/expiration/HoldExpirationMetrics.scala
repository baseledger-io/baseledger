package features.wallet.expiration

import scala.concurrent.ExecutionContext

import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory

import features.persistence.R2dbcSessionProvider

/**
 * Registers the `hold_expiration_queue_depth` async gauge.
 *
 * ==Why==
 * The single most useful number for spotting a stuck hold-expiration
 * dispatcher: if this stops decreasing while traffic continues, the
 * dispatcher is wedged.
 *
 * ==When==
 * Called once at startup, after `Observability.init` and after the
 * [[HoldExpirationRepository]] is constructed.
 *
 * ==How to extend==
 * To add an "overdue count" gauge (rows with `expires_at_ms < now`),
 * register an additional async gauge here using the same pattern.
 */
object HoldExpirationMetrics:
  private val log = LoggerFactory.getLogger(getClass)

  def register(otel: OpenTelemetry, provider: R2dbcSessionProvider)(using ExecutionContext): Unit =
    val meter = otel.getMeter("baseledger.wallet.expiration")
    val _ = meter
      .gaugeBuilder("hold_expiration_queue_depth")
      .setDescription("Total rows in the hold_expirations table")
      .setUnit("1")
      .ofLongs()
      .buildWithCallback { measurement =>
        provider
          .withReadSession(HoldExpirationRepository.countAll)
          .map(measurement.record)
          .recover { case t => log.warn("hold_expiration_queue_depth gauge query failed: {}", t.getMessage) }
      }
