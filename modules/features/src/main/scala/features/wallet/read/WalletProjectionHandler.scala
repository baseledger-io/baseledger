package features.wallet.read

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.Done
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.projection.r2dbc.scaladsl.{ R2dbcHandler, R2dbcSession }

import domain.WalletProtocol
import domain.wallet.*

/**
 * Per-envelope projection handler. Pattern-matches `WalletProtocol.Event`
 * and orchestrates [[WalletRepository]] calls against the session supplied
 * by `pekko-projection-r2dbc` (same session that commits the offset, so
 * write + offset commit are one transaction).
 */
class WalletProjectionHandler(using ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[WalletProtocol.Event]]:

  import WalletRepository.*

  /** Handles a single envelope; dispatches to per-event SQL via `WalletRepository`. */
  override def process(session: R2dbcSession, envelope: EventEnvelope[WalletProtocol.Event]): Future[Done] =
    envelope.eventOption match
      case Some(event) => handle(session, event)
      case None        => Future.successful(Done)

  private def handle(session: R2dbcSession, event: WalletProtocol.Event): Future[Done] =
    event match
      case e: TokensAdded =>
        for
          _ <- upsertBalance(session, e.id, availableDelta = e.amount, reservedDelta = 0L, ts = e.timestamp)
          _ <- insertTransaction(session, e.id, "ADDED", e.amount, None, e.idempotencyKey)
        yield Done

      case e: TokensReserved =>
        for
          _ <- upsertBalance(session, e.id, availableDelta = -e.amount, reservedDelta = e.amount, ts = e.timestamp)
          _ <- insertTransaction(session, e.id, "RESERVED", e.amount, Some(e.holdId), e.idempotencyKey)
          _ <- upsertHold(session, e.holdId, e.id, e.expiresAtMs, e.amount)
        yield Done

      case e: TokensSpent =>
        findHold(session, e.holdId).flatMap:
          case Some(hold) =>
            val releaseAmount = hold.amount - e.amount
            for
              _ <- upsertBalance(session, e.id, availableDelta = releaseAmount, reservedDelta = -hold.amount, ts = e.timestamp)
              _ <- insertTransaction(session, e.id, "SPENT", e.amount, Some(e.holdId), e.idempotencyKey)
              _ <- deleteHold(session, e.holdId)
            yield Done
          case None =>
            for
              _ <- upsertBalance(session, e.id, availableDelta = 0L, reservedDelta = -e.amount, ts = e.timestamp)
              _ <- insertTransaction(session, e.id, "SPENT", e.amount, Some(e.holdId), e.idempotencyKey)
            yield Done

      case e: TokensReleased =>
        findHold(session, e.holdId).flatMap:
          case Some(hold) =>
            for
              _ <- upsertBalance(session, e.id, availableDelta = hold.amount, reservedDelta = -hold.amount, ts = e.timestamp)
              _ <- insertTransaction(session, e.id, "RELEASED", hold.amount, Some(e.holdId), e.idempotencyKey)
              _ <- deleteHold(session, e.holdId)
            yield Done
          case None =>
            Future.successful(Done)
