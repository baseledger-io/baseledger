package features.persistence

import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.FutureConverters.*

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.r2dbc.ConnectionFactoryProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcSession

import io.r2dbc.spi.{ Connection, ConnectionFactory }
import reactor.core.publisher.Mono

/**
 * Borrows an `R2dbcSession` (with its own short-lived transaction) for
 * callers that live outside the projection pipeline — HTTP routes, the
 * hold-expiration dispatcher, Prometheus gauge callbacks, the health probe.
 *
 * The projection pipeline already supplies its own session per envelope
 * (the same one that commits the offset), so it must NOT go through here.
 *
 * The underlying connection pool is shared with `pekko-persistence-r2dbc`:
 * resolving the same config path returns the same pooled `ConnectionFactory`.
 */
final class R2dbcSessionProvider private (connectionFactory: ConnectionFactory)(using system: ActorSystem[?]):

  private given ExecutionContext = system.executionContext

  def withSession[A](fn: R2dbcSession => Future[A]): Future[A] =
    acquire().flatMap { conn =>
      val attempt =
        for
          _ <- toFuture(conn.beginTransaction())
          result <- fn(new R2dbcSession(conn))
          _ <- toFuture(conn.commitTransaction())
        yield result

      attempt
        .recoverWith { case err =>
          toFuture(conn.rollbackTransaction())
            .recover { case _ => () }
            .flatMap(_ => Future.failed(err))
        }
        .andThen { case _ => toFuture(conn.close()) }
    }

  def withReadSession[A](fn: R2dbcSession => Future[A]): Future[A] =
    acquire().flatMap { conn =>
      val attempt = fn(new R2dbcSession(conn))
      attempt.andThen { case _ => toFuture(conn.close()) }
    }

  private def acquire(): Future[Connection] =
    Mono.from(connectionFactory.create()).toFuture.asScala

  private def toFuture(p: org.reactivestreams.Publisher[Void]): Future[Unit] =
    Mono.from(p).toFuture.asScala.map(_ => ())

object R2dbcSessionProvider:

  /** Default config path matches the journal so the pool is shared. */
  val DefaultConfigPath: String = "pekko.persistence.r2dbc.connection-factory"

  def apply(system: ActorSystem[?]): R2dbcSessionProvider =
    apply(system, DefaultConfigPath)

  def apply(system: ActorSystem[?], configPath: String): R2dbcSessionProvider =
    val cf = ConnectionFactoryProvider(system).connectionFactoryFor(configPath)
    new R2dbcSessionProvider(cf)(using system)
