package features.wallet.expiration

import java.time.Instant

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{ ClusterSingleton, SingletonActor }

import domain.WalletActor
import domain.WalletProtocol.Response
import domain.wallet.ReleaseTokens

/**
 * Cluster singleton that periodically scans `hold_expirations` for due rows
 * and dispatches `ReleaseTokens` commands to the corresponding wallet entities.
 *
 * State machine:
 *   - `ready`      : waiting for the next `Tick`. A single-shot timer is
 *                    scheduled to deliver one `Tick` after `PollInterval`.
 *   - `processing` : a `findDue` query is in flight. Further `Tick`s are
 *                    ignored. Completion arrives as `PollDone(Try[...])`,
 *                    after which we reschedule the next `Tick` and return
 *                    to `ready`.
 */
object HoldExpirationDispatcher:

  enum Command:
    case Tick
    case PollDone(result: Try[Seq[HoldExpirationRow]])

  import Command.*

  private val PollInterval: FiniteDuration = 5.seconds
  private val BatchSize: Int               = 200
  private val TickTimerKey                 = "poll-tick"

  def init(system: ActorSystem[?], repo: HoldExpirationRepository, sharding: ClusterSharding): ActorRef[Command] =
    ClusterSingleton(system)
      .init(SingletonActor(behavior(repo, sharding), "hold-expiration-dispatcher"))

  private def behavior(repo: HoldExpirationRepository, sharding: ClusterSharding): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.self ! Tick  // Bootstrap: kick off the first poll immediately, then self-clock.
        ready(context, timers, repo, sharding)
      }
    }

  private def ready(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      repo: HoldExpirationRepository,
      sharding: ClusterSharding
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case Tick =>
        val now = Instant.now().toEpochMilli
        context.pipeToSelf(repo.findDue(now, BatchSize))(PollDone.apply)
        processing(context, timers, repo, sharding)

      case _: PollDone =>
        Behaviors.same // Stale completion (shouldn't happen in this state); ignore.
    }

  private def processing(
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      repo: HoldExpirationRepository,
      sharding: ClusterSharding
  ): Behavior[Command] =
    given resolver: ActorRefResolver = ActorRefResolver(context.system)
    val ignoreRef: String            = resolver.toSerializationFormat(context.system.ignoreRef[Response])

    Behaviors.receiveMessage {
      case Tick =>
        // Already polling; drop the spurious tick.
        Behaviors.same

      case PollDone(result) =>
        result match
          case Success(rows) =>
            rows.foreach { row =>
              val cmd = ReleaseTokens(
                id = row.walletId,
                idempotencyKey = s"auto-release-${row.holdId}",
                holdId = row.holdId,
                replyTo = ignoreRef
              )
              sharding
                .entityRefFor(WalletActor.TypeKey, row.walletId)
                .tell(cmd)
            }
          case Failure(ex) =>
            context.log.warn("hold expiration scan failed", ex)

        // Schedule the next poll and go back to ready.
        timers.startSingleTimer(TickTimerKey, Tick, PollInterval)
        ready(context, timers, repo, sharding)
    }
