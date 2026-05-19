package features.wallet.expiration

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcSession

final case class HoldExpirationRow(holdId: String, walletId: String, expiresAtMs: Long, amount: Long)

/**
 * Read access to the `hold_expirations` table. The projection itself writes
 * to this table inline via [[features.wallet.read.WalletRepository.upsertHold]]
 * / [[features.wallet.read.WalletRepository.deleteHold]]; this object is for
 * everything that *reads* the table (dispatcher poll + Prometheus gauge).
 */
object HoldExpirationRepository:

  /** Used by the dispatcher to find releases whose TTL has elapsed. */
  def findDue(session: R2dbcSession, nowMs: Long, limit: Int)(using ExecutionContext): Future[IndexedSeq[HoldExpirationRow]] =
    val stmt = session
      .createStatement(
        "SELECT hold_id, wallet_id, expires_at_ms, amount FROM hold_expirations " +
          "WHERE expires_at_ms <= $1 ORDER BY expires_at_ms ASC LIMIT $2"
      )
      .bind(0, nowMs)
      .bind(1, limit)

    session.select(stmt): row =>
      HoldExpirationRow(
        row.get("hold_id", classOf[String]),
        row.get("wallet_id", classOf[String]),
        row.get("expires_at_ms", classOf[java.lang.Long]).longValue(),
        row.get("amount", classOf[java.lang.Long]).longValue()
      )

  /** Total number of pending hold-expiration rows, exposed as a Prometheus gauge. */
  def countAll(session: R2dbcSession)(using ExecutionContext): Future[Long] =
    val stmt = session.createStatement("SELECT COUNT(*) AS c FROM hold_expirations")
    session
      .selectOne(stmt)(row => row.get("c", classOf[java.lang.Long]).longValue())
      .map(_.getOrElse(0L))

