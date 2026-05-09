package features.wallet.expiration

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }
import scala.util.{ Failure, Success, Try }

import io.opentelemetry.api.OpenTelemetry
import org.slf4j.LoggerFactory

/**
 * Registers the `hold_expiration_queue_depth` async gauge.
 *
 * ==Why==
 * The single most useful number for spotting a stuck hold-expiration
 * dispatcher: if this stops decreasing while traffic continues, the
 * dispatcher is wedged.
 *
 * ==When==
 * Called once at startup, after [[Observability.init]] and after the
 * [[HoldExpirationRepository]] is constructed.
 *
 * ==How to extend==
 * To add an "overdue count" gauge (rows with `expires_at_ms < now`),
 * register an additional async gauge here using the same pattern.
 */
object HoldExpirationMetrics:
  private val log = LoggerFactory.getLogger(getClass)

  // Block briefly on the count query inside the gauge callback. The
  // callback runs on OTel's own scrape thread and is short-lived; a
  // 2-second cap prevents a misbehaving DB from stalling the scrape.
  private val GaugeQueryTimeout: FiniteDuration = 2.seconds

  def register(otel: OpenTelemetry, repo: HoldExpirationRepository)(using ExecutionContext): Unit =
    val meter = otel.getMeter("baseledger.wallet.expiration")
    val _ = meter
      .gaugeBuilder("hold_expiration_queue_depth")
      .setDescription("Total rows in the hold_expirations table")
      .setUnit("1")
      .ofLongs()
      .buildWithCallback { measurement =>
        Try(Await.result(repo.countAll(), GaugeQueryTimeout)) match
          case Success(n) => measurement.record(n)
          case Failure(t) =>
            log.warn("hold_expiration_queue_depth gauge query failed: {}", t.getMessage)
      }
