package app

import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/**
 * Lightweight migration check placeholder. Actual migrations are handled
 * by the docker-compose sidecar for Native Image stability.
 */
object Migrations:

  private val log = LoggerFactory.getLogger(getClass)

  def run(config: Config): Unit =
    log.info("Migrations: Skipping internal Flyway run (handled by sidecar).")
