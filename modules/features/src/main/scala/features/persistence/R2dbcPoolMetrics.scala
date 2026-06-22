package features.persistence

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.{ AttributeKey, Attributes }
import io.r2dbc.pool.{ ConnectionPool, PoolMetrics }
import org.slf4j.LoggerFactory

/**
 * Registers async gauges that expose the live state of the R2DBC connection
 * pools (`write` = journal/snapshot, `read` = projection / HTTP GET / health).
 *
 * ==Why==
 * A thread dump cannot tell whether a pool is the throughput cap: the pool is a
 * set of heap `Connection` objects multiplexed over a fixed set of Netty I/O
 * threads, so saturation only shows up as callers *waiting to acquire*. The
 * single most diagnostic number is therefore `r2dbc_pool_pending` — if it stays
 * > 0 under load while writes plateau, the pool (`max-size`) is the bottleneck;
 * if it stays ~0, the pool is exonerated and the cap is upstream.
 *
 * ==Gauges== (each tagged `pool="write"|"read"`)
 *   - `r2dbc_pool_acquired`       connections currently checked out
 *   - `r2dbc_pool_idle`           established but idle connections
 *   - `r2dbc_pool_allocated`      total established connections
 *   - `r2dbc_pool_pending`        callers blocked waiting to acquire  ← key
 *   - `r2dbc_pool_max_allocated`  configured `max-size`
 *
 * ==When==
 * Called once at startup, after `Observability.init`. Resolving the same config
 * path returns the same pooled `ConnectionFactory` the journal/read side use, so
 * these gauges observe the real pools (no new connections are created).
 */
object R2dbcPoolMetrics:
  private val log = LoggerFactory.getLogger(getClass)
  private val PoolKey: AttributeKey[String] = AttributeKey.stringKey("pool")

  /** Config path -> `pool` attribute label. Paths match [[event-journal.conf]]. */
  private val PoolPaths: List[(String, String)] = List(
    "pekko.persistence.r2dbc.connection-factory" -> "write",
    "read-side-connection-factory" -> "read"
  )

  def register(otel: OpenTelemetry)(using system: ActorSystem[?]): Unit =
    val provider = ConnectionFactoryProvider(system)

    val pools: List[(String, ConnectionPool)] =
      PoolPaths.flatMap { (path, label) =>
        provider.connectionFactoryFor(path) match
          case cp: ConnectionPool => Some(label -> cp)
          case other =>
            log.warn(
              "R2DBC factory at '{}' is {}, not a ConnectionPool; pool gauges for '{}' skipped.",
              path,
              other.getClass.getName,
              label
            )
            None
      }

    if pools.isEmpty then
      log.warn("R2dbcPoolMetrics: no ConnectionPool resolved; pool gauges not registered.")
    else
      val meter = otel.getMeter("baseledger.r2dbc.pool")

      def gauge(name: String, description: String)(read: PoolMetrics => Int): Unit =
        val _ = meter
          .gaugeBuilder(name)
          .setDescription(description)
          .setUnit("1")
          .ofLongs()
          .buildWithCallback { measurement =>
            pools.foreach { (label, cp) =>
              val metrics = cp.getMetrics
              if metrics.isPresent then
                measurement.record(read(metrics.get).toLong, Attributes.of(PoolKey, label))
            }
          }

      gauge("r2dbc_pool_acquired", "Connections currently checked out")(_.acquiredSize)
      gauge("r2dbc_pool_idle", "Established but idle connections")(_.idleSize)
      gauge("r2dbc_pool_allocated", "Total established connections")(_.allocatedSize)
      gauge("r2dbc_pool_pending", "Callers blocked waiting to acquire a connection")(_.pendingAcquireSize)
      gauge("r2dbc_pool_max_allocated", "Configured pool max-size")(_.getMaxAllocatedSize)

      log.info("R2dbcPoolMetrics: registered pool gauges for {}.", pools.map(_._1).mkString(", "))
