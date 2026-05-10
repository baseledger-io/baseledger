package features.wallet.read

import java.util.UUID

import scala.concurrent.{ ExecutionContext, Future }

import domain.WalletProtocol
import domain.wallet.*
import features.wallet.expiration.{ HoldExpirationRow, HoldExpirations }
import slick.dbio.DBIO
import slick.jdbc.JdbcBackend
import slick.jdbc.PostgresProfile.api.*

case class WalletRow(
    id: String,
    availableBalance: Long,
    reservedBalance: Long,
    lastEventAt: Long
)

case class TransactionRow(
    txId: UUID,
    walletId: String,
    eventType: String,
    amount: Long,
    holdId: Option[String],
    idempotencyKey: String
)

class Wallets(tag: Tag) extends Table[WalletRow](tag, "wallets"):
  def id = column[String]("id", O.PrimaryKey)
  def availableBalance = column[Long]("available_balance")
  def reservedBalance = column[Long]("reserved_balance")
  def lastEventAt = column[Long]("last_event_at")

  def * = (id, availableBalance, reservedBalance, lastEventAt).mapTo[WalletRow]

class WalletTransactions(tag: Tag) extends Table[TransactionRow](tag, "wallet_transactions"):
  def txId = column[UUID]("tx_id", O.PrimaryKey)
  def walletId = column[String]("wallet_id")
  def eventType = column[String]("event_type")
  def amount = column[Long]("amount")
  def holdId = column[Option[String]]("hold_id")
  def idempotencyKey = column[String]("idempotency_key")

  def * = (txId, walletId, eventType, amount, holdId, idempotencyKey).mapTo[TransactionRow]

class WalletRepository(db: JdbcBackend.Database):
  private val wallets = TableQuery[Wallets]
  private val transactions = TableQuery[WalletTransactions]
  private val holds = TableQuery[HoldExpirations]

  /**
   * Processes a single wallet event and updates the read-side state atomically.
   * This is called by the Pekko Projection handler.
   */
  def handleEvent(event: WalletProtocol.Event)(using ec: ExecutionContext): DBIO[Unit] =
    event match
      case e: TokensAdded =>
        for
          _ <- updateBalance(e.id, availableDelta = e.amount, reservedDelta = 0, e.timestamp)
          _ <- logTransaction(e.id, "ADDED", e.amount, None, e.idempotencyKey)
        yield ()

      case e: TokensReserved =>
        for
          _ <- updateBalance(e.id, availableDelta = -e.amount, reservedDelta = e.amount, e.timestamp)
          _ <- logTransaction(e.id, "RESERVED", e.amount, Some(e.holdId), e.idempotencyKey)
          _ <- holds.insertOrUpdate(HoldExpirationRow(e.holdId, e.id, e.expiresAtMs, e.amount))
        yield ()

      case e: TokensSpent =>
        // Partial capture: look up the original hold to compute the unrealized remainder.
        // The full hold is closed — spent portion is deducted, remainder returns to available.
        holds.filter(_.holdId === e.holdId).result.headOption.flatMap:
          case Some(hold) =>
            val releaseAmount = hold.amount - e.amount
            for
              _ <- updateBalance(e.id, availableDelta = releaseAmount, reservedDelta = -hold.amount, e.timestamp)
              _ <- logTransaction(e.id, "SPENT", e.amount, Some(e.holdId), e.idempotencyKey)
              _ <- holds.filter(_.holdId === e.holdId).delete
            yield ()
          case None =>
            // Hold not found in projection — fall back to simple deduction
            for
              _ <- updateBalance(e.id, availableDelta = 0, reservedDelta = -e.amount, e.timestamp)
              _ <- logTransaction(e.id, "SPENT", e.amount, Some(e.holdId), e.idempotencyKey)
            yield ()

      case e: TokensReleased =>
        // Use the 'holds' table as a memory to find the amount that was originally reserved
        holds.filter(_.holdId === e.holdId).result.headOption.flatMap:
          case Some(hold) =>
            for
              _ <- updateBalance(e.id, availableDelta = hold.amount, reservedDelta = -hold.amount, e.timestamp)
              _ <- logTransaction(e.id, "RELEASED", hold.amount, Some(e.holdId), e.idempotencyKey)
              _ <- holds.filter(_.holdId === e.holdId).delete
            yield ()
          case None =>
            DBIO.successful(())

  private def updateBalance(id: String, availableDelta: Long, reservedDelta: Long, ts: Long): DBIO[Int] =
    sqlu"""INSERT INTO wallets (id, available_balance, reserved_balance, last_event_at)
           VALUES ($id, $availableDelta, $reservedDelta, $ts)
           ON CONFLICT (id) DO UPDATE SET
             available_balance = wallets.available_balance + $availableDelta,
             reserved_balance = wallets.reserved_balance + $reservedDelta,
             last_event_at = EXCLUDED.last_event_at
           WHERE EXCLUDED.last_event_at > wallets.last_event_at"""

  private def logTransaction(walletId: String, eventType: String, amount: Long, holdId: Option[String], idempotencyKey: String): DBIO[Int] =
    val tx = TransactionRow(UUID.randomUUID(), walletId, eventType, amount, holdId, idempotencyKey)
    transactions += tx

  def findByIdAction(id: String): DBIO[Option[WalletRow]] =
    wallets.filter(_.id === id).result.headOption

  def findById(id: String): Future[Option[WalletRow]] =
    db.run(findByIdAction(id))
