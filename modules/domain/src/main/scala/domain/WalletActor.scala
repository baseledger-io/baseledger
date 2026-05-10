package domain

import java.time.Instant

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ ActorRefResolver, Behavior }
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria }

import domain.WalletProtocol.*
import domain.wallet.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

object WalletActor:

  // Structured Error Codes
  val CodeInsufficientBalance = "INSUFFICIENT_BALANCE"
  val CodeHoldAlreadyExists = "HOLD_ALREADY_EXISTS"
  val CodeHoldNotFound = "HOLD_NOT_FOUND"
  val CodeIdempotencyConflict = "IDEMPOTENCY_CONFLICT"
  val CodeIdempotencyKeyRequired = "IDEMPOTENCY_KEY_REQUIRED"
  val CodeWalletNotFound = "WALLET_NOT_FOUND"

  // Idempotency keys are retained for 24h after their event timestamp.
  private val IdempotencyTtlMs: Long = 24 * 60 * 60 * 1000L

  val TypeKey: EntityTypeKey[Command] = EntityTypeKey[Command]("Wallet")

  def init(sharding: ClusterSharding): Unit =
    sharding.init(Entity(TypeKey)(entityContext => WalletActor(entityContext.entityId)))

  def apply(entityId: String): Behavior[Command] =
    Behaviors.setup { context =>
      given ActorRefResolver = ActorRefResolver(context.system)

      EventSourcedBehavior.withEnforcedReplies[Command, Event, WalletState](
        persistenceId = PersistenceId(TypeKey.name, entityId),
        emptyState = WalletState(
          availableBalance = 0L.refineUnsafe[GreaterEqual[0L]],
          reservedBalance = 0L.refineUnsafe[GreaterEqual[0L]],
          activeHolds = Map.empty,
          recentIdempotencyKeys = Map.empty
        ),
        commandHandler = (state, command) => handleCommand(state, command),
        eventHandler = (state, event) => applyEvent(state, event)
      ).withTagger(_ => Set("wallet"))
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
    }

  private def handleCommand(
      state: WalletState,
      command: Command
  )(using ActorRefResolver): ReplyEffect[Event, WalletState] =

    val rawNow = Instant.now().toEpochMilli
    val now = math.max(rawNow, state.lastEventTimestamp + 1)

    extension [C](i: Long :| C)
      private def asLong: Long = i.asInstanceOf[Long]

    /**
     * Checks if this idempotency key was already used, and if so, whether
     * the parameters match.
     *
     * @return Right(true) if it's a perfect replay (Success),
     *         Right(false) if it's a new key (Continue),
     *         Left(ErrorCode) if it's a conflict.
     */
    def checkIdempotency(key: String, cmdType: String, amount: Long): Either[String, Boolean] =
      if key.trim.isEmpty then Left(CodeIdempotencyKeyRequired)
      else
        state.recentIdempotencyKeys.get(key) match
          case Some(prev) if prev.commandType == cmdType && prev.amount == amount => Right(true)
          case Some(_) => Left(CodeIdempotencyConflict)
          case None => Right(false)

    command match
      case cmd: AddTokens =>
        checkIdempotency(cmd.idempotencyKey, "AddTokens", cmd.amount.asLong) match
          case Right(true) =>
            Effect.reply(cmd.replyToRef)(WalletSuccess(cmd.id, state.availableBalance, state.reservedBalance))
          case Left(code) =>
            Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, s"Idempotency error: $code", code))
          case Right(false) =>
            Effect.persist[Event, WalletState](TokensAdded(cmd.id, cmd.idempotencyKey, cmd.amount, now, cmd.metadata))
              .thenReply(cmd.replyToRef)(newState => WalletSuccess(cmd.id, newState.availableBalance, newState.reservedBalance))

      case cmd: ReserveTokens =>
        checkIdempotency(cmd.idempotencyKey, "ReserveTokens", cmd.amount.asLong) match
          case Right(true) =>
            Effect.reply(cmd.replyToRef)(WalletSuccess(cmd.id, state.availableBalance, state.reservedBalance))
          case Left(code) =>
            Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, s"Idempotency error: $code", code))
          case Right(false) =>
            if state.availableBalance < cmd.amount then
              Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, "Insufficient available balance", CodeInsufficientBalance))
            else if state.activeHolds.contains(cmd.holdId) then
              Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, "Hold ID already exists", CodeHoldAlreadyExists))
            else
              val expiresAtMs = now + cmd.ttlSeconds * 1000L
              Effect.persist[Event, WalletState](
                TokensReserved(cmd.id, cmd.idempotencyKey, cmd.holdId, cmd.amount, now, expiresAtMs, cmd.metadata)
              ).thenReply(cmd.replyToRef)(newState =>
                WalletSuccess(cmd.id, newState.availableBalance, newState.reservedBalance)
              )

      case cmd: SpendTokens =>
        checkIdempotency(cmd.idempotencyKey, "SpendTokens", cmd.amount.asLong) match
          case Right(true) =>
            Effect.reply(cmd.replyToRef)(WalletSuccess(cmd.id, state.availableBalance, state.reservedBalance))
          case Left(code) =>
            Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, s"Idempotency error: $code", code))
          case Right(false) =>
            state.activeHolds.get(cmd.holdId) match
              case Some(heldAmount) =>
                if cmd.amount.asLong > heldAmount.asLong then
                  Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, "Cannot capture more than the reserved hold amount", CodeInsufficientBalance))
                else
                  Effect.persist[Event, WalletState](TokensSpent(cmd.id, cmd.idempotencyKey, cmd.holdId, cmd.amount, now, cmd.metadata))
                    .thenReply(cmd.replyToRef)(newState => WalletSuccess(cmd.id, newState.availableBalance, newState.reservedBalance))
              case None =>
                Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, "Hold ID not found or already processed", CodeHoldNotFound))

      case cmd: ReleaseTokens =>
        checkIdempotency(cmd.idempotencyKey, "ReleaseTokens", 0L) match
          case Right(true) =>
            Effect.reply(cmd.replyToRef)(WalletSuccess(cmd.id, state.availableBalance, state.reservedBalance))
          case Left(code) =>
            Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, s"Idempotency error: $code", code))
          case Right(false) =>
            if state.activeHolds.contains(cmd.holdId) then
              Effect.persist[Event, WalletState](TokensReleased(cmd.id, cmd.idempotencyKey, cmd.holdId, now, cmd.metadata))
                .thenReply(cmd.replyToRef)(newState => WalletSuccess(cmd.id, newState.availableBalance, newState.reservedBalance))
            else
              Effect.reply(cmd.replyToRef)(WalletFailure(cmd.id, "Hold ID not found or already processed", CodeHoldNotFound))

  private def applyEvent(state: WalletState, event: Event): WalletState =

    extension [C](i: Long :| C)
      private def asLong: Long = i.asInstanceOf[Long]

    // Record this event's idempotency key and metadata, and evict entries older
    // than the current event's timestamp - IdempotencyTtlMs.
    // Using event.timestamp keeps replay deterministic.
    def trackIdempotency(s: WalletState, key: String, event: Event): WalletState =
      val eventTimestamp = event match
        case e: TokensAdded => e.timestamp
        case e: TokensReserved => e.timestamp
        case e: TokensSpent => e.timestamp
        case e: TokensReleased => e.timestamp

      val (cmdType, amount) = event match
        case e: TokensAdded => ("AddTokens", e.amount.asLong)
        case e: TokensReserved => ("ReserveTokens", e.amount.asLong)
        case e: TokensSpent => ("SpendTokens", e.amount.asLong)
        case e: TokensReleased => ("ReleaseTokens", 0L)

      val cutoff = eventTimestamp - IdempotencyTtlMs
      val pruned = s.recentIdempotencyKeys.filter { case (_, pc) => pc.timestamp >= cutoff }

      s.copy(
        recentIdempotencyKeys = pruned + (key -> ProcessedCommand(cmdType, amount, eventTimestamp)),
        lastEventTimestamp = eventTimestamp
      )

    event match
      case e: TokensAdded =>
        val s = trackIdempotency(state, e.idempotencyKey, e)
        s.copy(availableBalance = (s.availableBalance.asLong + e.amount.asLong).refineUnsafe[GreaterEqual[0L]])

      case e: TokensReserved =>
        val s = trackIdempotency(state, e.idempotencyKey, e)
        s.copy(
          availableBalance = (s.availableBalance.asLong - e.amount.asLong).refineUnsafe[GreaterEqual[0L]],
          reservedBalance = (s.reservedBalance.asLong + e.amount.asLong).refineUnsafe[GreaterEqual[0L]],
          activeHolds = s.activeHolds + (e.holdId -> e.amount)
        )

      case e: TokensSpent =>
        val s = trackIdempotency(state, e.idempotencyKey, e)
        val heldAmount = s.activeHolds(e.holdId)
        val captureAmount = e.amount
        val releaseAmount = heldAmount.asLong - captureAmount.asLong
        s.copy(
          availableBalance = (s.availableBalance.asLong + releaseAmount).refineUnsafe[GreaterEqual[0L]],
          reservedBalance = (s.reservedBalance.asLong - heldAmount.asLong).refineUnsafe[GreaterEqual[0L]],
          activeHolds = s.activeHolds - e.holdId
        )

      case e: TokensReleased =>
        val s = trackIdempotency(state, e.idempotencyKey, e)
        val amount = s.activeHolds(e.holdId)
        s.copy(
          availableBalance = (s.availableBalance.asLong + amount.asLong).refineUnsafe[GreaterEqual[0L]],
          reservedBalance = (s.reservedBalance.asLong - amount.asLong).refineUnsafe[GreaterEqual[0L]],
          activeHolds = s.activeHolds - e.holdId
        )
