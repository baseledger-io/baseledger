package common

import com.typesafe.config.ConfigFactory

/**
 * Test-only fallbacks for the env-driven keys in `application.conf`.
 *
 * Production builds require these to be set in the environment (no defaults)
 * so deployments fail fast on misconfiguration. Tests don't need real values
 * for most of them — Testcontainers / per-test config overrides the DB host /
 * ports later — so we seed JVM system properties before `ConfigFactory.load()`
 * resolves `${VAR}` substitutions.
 *
 * HOCON resolves `${VAR}` against system properties identically to env vars,
 * so test code only needs to call `TestEnv.init()` (or reference any member)
 * before the first config load.
 */
object TestEnv {

  private val defaults: Map[String, String] = Map(
    "POSTGRES_HOST" -> "localhost",
    "POSTGRES_PORT" -> "5432",
    "POSTGRES_DB" -> "baseledger",
    "POSTGRES_USER" -> "baseledger",
    "POSTGRES_PASSWORD" -> "password",
    "HTTP_HOST" -> "127.0.0.1",
    "HTTP_PORT" -> "8000",
    "PEKKO_REMOTE_HOSTNAME" -> "127.0.0.1",
    "PEKKO_REMOTE_PORT" -> "2551",
    "PEKKO_LOGLEVEL" -> "INFO",
    "PEKKO_NUMBER_OF_SHARDS" -> "100",
    "PASSIVATE_IDLE_ENTITY_AFTER" -> "15 minutes",
    "PROJECTION_DISPATCHER_FIXED_POOL_SIZE" -> "8",
    "WRITE_PERSISTENCE_DISPATCHER_FIXED_POOL_SIZE" -> "256",
    "R2DBC_POOL_MAX_SIZE" -> "256",
    "R2DBC_POOL_INITIAL_SIZE" -> "64",
    "READ_R2DBC_POOL_MAX_SIZE" -> "256",
    "READ_R2DBC_POOL_INITIAL_SIZE" -> "64",
    "HTTP_SERVER_BACKLOG" -> "8192",
    "HTTP_SERVER_MAX_CONNECTIONS" -> "65536",
    "HTTP_SERVER_HTTP2_MAX_CONCURRENT_STREAMS" -> "1024",
    "HTTP_SERVER_PIPELINING_LIMIT" -> "32",
    "HTTP_SERVER_ENABLE_HTTP2" -> "on",
    "HTTP_SERVER_REQUEST_TIMEOUT" -> "30 s",
    "HTTP_SERVER_USE_DISPATCHER" -> "http-dispatcher",
    "HTTP_DISPATCHER_PARALLELISM_MIN" -> "16",
    "HTTP_DISPATCHER_PARALLELISM_FACTOR" -> "3.0",
    "HTTP_DISPATCHER_PARALLELISM_MAX" -> "64",
    "HTTP_DISPATCHER_THROUGHPUT" -> "200",
    // Disable OpenTelemetry exporters in tests — the autoconfigure SDK
    // defaults to `otlp` against localhost:4317, which floods logs with
    // ConnectException when no collector is running.
    "OTEL_TRACES_EXPORTER" -> "none",
    "OTEL_METRICS_EXPORTER" -> "none",
    "OTEL_LOGS_EXPORTER" -> "none",
    "OTEL_SDK_DISABLED" -> "true"
  )

  private def missing(s: String): Boolean = Option(s).forall(_.isBlank)

  // Apply on object init so any reference triggers seeding exactly once.
  defaults.foreach { case (k, v) =>
    if (missing(System.getProperty(k)) && missing(System.getenv(k)))
      System.setProperty(k, v)
  }
  ConfigFactory.invalidateCaches()

  /** No-op call site used to force object initialization before config load. */
  def init(): Unit = ()
}
