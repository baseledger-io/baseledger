package app

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{ ActorSystem, Behavior, Scheduler }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.gracefulStop
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.projection.slick.SlickProjection
import org.apache.pekko.util.Timeout

import app.RootGuardian.RootCommand.Start
import domain.WalletActor
import features.health.HealthRoute
import features.observability.Observability
import features.wallet.WalletRoute
import features.wallet.expiration.{ HoldExpirationDispatcher, HoldExpirationMetrics, HoldExpirationRepository }
import features.wallet.read.{ WalletProjection, WalletRepository }
import slick.basic.DatabaseConfig

object RootGuardian:

  enum RootCommand:
    case Start

  def apply(): Behavior[RootCommand] = Behaviors.setup[RootCommand] { context =>
    context.log.info("System starting up...")

    given system: ActorSystem[?] = context.system
    given ec: ExecutionContext = context.executionContext
    given Scheduler = context.system.scheduler
    given Timeout(3.seconds)

    val sharding = ClusterSharding(context.system)
    WalletActor.init(sharding)
    context.log.info("RootGuardian: Sharding initialized.")

    val dbConfig = DatabaseConfig.forConfig[slick.jdbc.PostgresProfile]("pekko.projection.slick", context.system.settings.config)
    context.log.info("RootGuardian: DB Config loaded.")

    val repo = new WalletRepository(dbConfig.db.asInstanceOf[slick.jdbc.JdbcBackend.Database])
    val holdExpirationRepo = new HoldExpirationRepository(dbConfig.db.asInstanceOf[slick.jdbc.JdbcBackend.Database])

    // Start Projection
    val projectionBehavior = WalletProjection.createBehavior(context.system, repo)
    val walletProjectionRef = context.spawn(projectionBehavior, "wallet-projection")
    context.log.info("RootGuardian: Projection spawned.")

    // Hold-expiration dispatcher (cluster singleton, polls + sends ReleaseTokens)
    HoldExpirationDispatcher.init(context.system, holdExpirationRepo, sharding)
    context.log.info("RootGuardian: Hold expiration dispatcher initialized.")

    // Observability: build OpenTelemetry SDK with Prometheus scrape endpoint.
    val cfg = context.system.settings.config
    val prometheusPort =
      if cfg.hasPath("observability.prometheus.port") then cfg.getInt("observability.prometheus.port")
      else 9464
    val otel = Observability.init("baseledger", prometheusPort)
    context.log.info("RootGuardian: Observability initialized (Prometheus on :{}).", prometheusPort)

    HoldExpirationMetrics.register(otel.otel, holdExpirationRepo)
    context.log.info("RootGuardian: hold_expiration_queue_depth gauge registered.")

    val shutdown = CoordinatedShutdown(context.system)

    shutdown.addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "stop-wallet-projection") { () =>
      gracefulStop(walletProjectionRef.toClassic, 10.seconds, ProjectionBehavior.Stop).map(_ => Done)
    }

    // Give the dispatcher's in-flight pipeToSelf a moment to complete
    // before sharding shuts down. Singleton stop itself is automatic.
    shutdown.addTask(CoordinatedShutdown.PhaseServiceStop, "drain-hold-expiration-dispatcher") { () =>
      org.apache.pekko.pattern.after(2.seconds)(Future.successful(Done))(using context.system.classicSystem)
    }

    shutdown.addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdown-observability") { () =>
      Future { otel.shutdown(); Done }
    }
    context.log.info("RootGuardian: Coordinated shutdown tasks registered.")

    val walletRoute = WalletRoute(sharding, repo).route
    val healthRoute = new HealthRoute(dbConfig.db.asInstanceOf[slick.jdbc.JdbcBackend.Database])
    context.log.info("RootGuardian: Routes initialized.")

    val endpoints = walletRoute ++ healthRoute.endpoints

    context.log.info("RootGuardian: Configuring HTTP...")
    val routes: Route = configureHttp(endpoints, otel.otel)
    context.log.info("RootGuardian: HTTP configured.")

    val port = context.system.settings.config.getInt("http.port")
    val host =
      if context.system.settings.config.hasPath("http.host") then
        context.system.settings.config.getString("http.host")
      else
        "127.0.0.1"

    // Wait for the server to actually start before signaling "ready"
    context.log.info(s"RootGuardian: Starting HTTP server on $host:$port...")
    startHttpServer(routes, host, port)
    context.log.info("RootGuardian: HTTP server start initiated.")

    // Keep the guardian alive instead of returning Behaviors.empty
    Behaviors.receiveMessage {
      case Start =>
        context.log.info("Root guardian received start message.")
        Behaviors.same
    }
  }
