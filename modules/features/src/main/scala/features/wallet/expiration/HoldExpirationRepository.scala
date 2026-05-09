package features.wallet.expiration

import scala.concurrent.{ExecutionContext, Future}

import slick.dbio.DBIO
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api._

case class HoldExpirationRow(holdId: String, walletId: String, expiresAtMs: Long, amount: Long)

class HoldExpirations(tag: Tag) extends Table[HoldExpirationRow](tag, "hold_expirations"):
  def holdId      = column[String]("hold_id", O.PrimaryKey)
  def walletId    = column[String]("wallet_id")
  def expiresAtMs = column[Long]("expires_at_ms")
  def amount      = column[Long]("amount")

  def * = (holdId, walletId, expiresAtMs, amount).mapTo[HoldExpirationRow]

class HoldExpirationRepository(db: JdbcBackend.Database)(using ExecutionContext):
  private val table = TableQuery[HoldExpirations]

  /** Used by the projection (returns a DBIO so it can run inside the projection's transaction). */
  def insertIfAbsentAction(row: HoldExpirationRow): DBIO[Int] =
    table.insertOrUpdate(row)

  def deleteByHoldIdAction(holdId: String): DBIO[Int] =
    table.filter(_.holdId === holdId).delete

  /** Used by the dispatcher to find releases whose TTL has elapsed. */
  def findDue(nowMs: Long, limit: Int): Future[Seq[HoldExpirationRow]] =
    db.run(
      table
        .filter(_.expiresAtMs <= nowMs)
        .sortBy(_.expiresAtMs.asc)
        .take(limit)
        .result
    )

  /** Total number of pending hold-expiration rows, exposed as a Prometheus
   *  gauge to spot a stuck dispatcher. */
  def countAll(): Future[Long] =
    db.run(table.length.result).map(_.toLong)

