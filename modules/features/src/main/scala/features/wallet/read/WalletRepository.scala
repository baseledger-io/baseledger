package features.wallet.read

import java.util.UUID

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcSession

import features.wallet.expiration.HoldExpirationRow

final case class WalletRow(
    id: String,
    availableBalance: Long,
    reservedBalance: Long,
    lastEventAt: Long
)

/**
 * Pure SQL access to the wallet read model. Every method takes the
 * `R2dbcSession` it should run on so the caller controls the transactional
 * context — the projection handler supplies its own session (offset commit +
 * write share one tx), HTTP / dispatcher / metrics callers borrow one via
 * [[features.persistence.R2dbcSessionProvider]].
 */
object WalletRepository:

  def findById(session: R2dbcSession, id: String)(using ExecutionContext): Future[Option[WalletRow]] =
    val stmt = session
      .createStatement(
        "SELECT id, available_balance, reserved_balance, last_event_at FROM wallets WHERE id = $1"
      )
      .bind(0, id)

    session.selectOne(stmt): row =>
      WalletRow(
        row.get("id", classOf[String]),
        row.get("available_balance", classOf[java.lang.Long]).longValue(),
        row.get("reserved_balance", classOf[java.lang.Long]).longValue(),
        row.get("last_event_at", classOf[java.lang.Long]).longValue()
      )

  def upsertBalance(
      session: R2dbcSession,
      id: String,
      availableDelta: Long,
      reservedDelta: Long,
      ts: Long
  )(using ExecutionContext): Future[Long] =
    val stmt = session
      .createStatement(
        """INSERT INTO wallets (id, available_balance, reserved_balance, last_event_at)
          |VALUES ($1, $2, $3, $4)
          |ON CONFLICT (id) DO UPDATE SET
          |  available_balance = wallets.available_balance + EXCLUDED.available_balance,
          |  reserved_balance  = wallets.reserved_balance  + EXCLUDED.reserved_balance,
          |  last_event_at     = EXCLUDED.last_event_at
          |WHERE EXCLUDED.last_event_at > wallets.last_event_at""".stripMargin
      )
      .bind(0, id)
      .bind(1, availableDelta)
      .bind(2, reservedDelta)
      .bind(3, ts)
    session.updateOne(stmt)

  def insertTransaction(
      session: R2dbcSession,
      walletId: String,
      eventType: String,
      amount: Long,
      holdId: Option[String],
      idempotencyKey: String
  )(using ExecutionContext): Future[Long] =
    val stmt = session
      .createStatement(
        """INSERT INTO wallet_transactions (tx_id, wallet_id, event_type, amount, hold_id, idempotency_key)
          |VALUES ($1, $2, $3, $4, $5, $6)""".stripMargin
      )
      .bind(0, UUID.randomUUID())
      .bind(1, walletId)
      .bind(2, eventType)
      .bind(3, amount)
    val boundStmt = (holdId match
      case Some(h) => stmt.bind(4, h)
      case None => stmt.bindNull(4, classOf[String])
    ).bind(5, idempotencyKey)
    session.updateOne(boundStmt)

  def upsertHold(
      session: R2dbcSession,
      holdId: String,
      walletId: String,
      expiresAtMs: Long,
      amount: Long
  )(using ExecutionContext): Future[Long] =
    val stmt = session
      .createStatement(
        """INSERT INTO hold_expirations (hold_id, wallet_id, expires_at_ms, amount)
          |VALUES ($1, $2, $3, $4)
          |ON CONFLICT (hold_id) DO UPDATE SET
          |  wallet_id     = EXCLUDED.wallet_id,
          |  expires_at_ms = EXCLUDED.expires_at_ms,
          |  amount        = EXCLUDED.amount""".stripMargin
      )
      .bind(0, holdId)
      .bind(1, walletId)
      .bind(2, expiresAtMs)
      .bind(3, amount)

    session.updateOne(stmt)

  def deleteHold(session: R2dbcSession, holdId: String)(using ExecutionContext): Future[Long] =
    val stmt = session
      .createStatement("DELETE FROM hold_expirations WHERE hold_id = $1")
      .bind(0, holdId)

    session.updateOne(stmt)

  def findHold(session: R2dbcSession, holdId: String)(using ExecutionContext): Future[Option[HoldExpirationRow]] =

    val stmt = session
      .createStatement("SELECT hold_id, wallet_id, expires_at_ms, amount FROM hold_expirations WHERE hold_id = $1")
      .bind(0, holdId)

    session.selectOne(stmt): row =>
      HoldExpirationRow(
        row.get("hold_id", classOf[String]),
        row.get("wallet_id", classOf[String]),
        row.get("expires_at_ms", classOf[java.lang.Long]).longValue(),
        row.get("amount", classOf[java.lang.Long]).longValue()
      )
