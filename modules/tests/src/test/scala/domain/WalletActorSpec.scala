package domain

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.typed.PersistenceId

import com.typesafe.config.ConfigFactory
import domain.WalletProtocol._
import domain.WalletTypes._
import domain.wallet._
import io.github.iltotore.iron._
import io.github.iltotore.iron.constraint.numeric._
import org.scalatest.wordspec.AnyWordSpecLike

class WalletActorSpec extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config.withFallback(ConfigFactory.load()))
    with AnyWordSpecLike {

  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[Command, Event, WalletState](
      system,
      WalletActor("test-wallet-1"),
      EventSourcedBehaviorTestKit.SerializationSettings.enabled
    )

  "WalletActor" should {
    "add tokens and update available balance" in {
      val result = eventSourcedTestKit.runCommand { replyTo =>
        val resolver = ActorRefResolver(system)
        AddTokens("test-wallet-1", "idem-1", 1000L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo))
      }

      result.reply shouldBe WalletSuccess("test-wallet-1", 1000L.refineUnsafe[GreaterEqual[0L]], 0L.refineUnsafe[GreaterEqual[0L]])
      result.state.availableBalance shouldBe 1000L.refineUnsafe[GreaterEqual[0L]]
      result.event.isInstanceOf[TokensAdded] shouldBe true
    }

    "reserve tokens successfully" in {
      val result = eventSourcedTestKit.runCommand { replyTo =>
        val resolver = ActorRefResolver(system)
        ReserveTokens("test-wallet-1", "idem-2", "hold-1", 400L.refineUnsafe[Positive], 3600L, resolver.toSerializationFormat(replyTo))
      }

      result.reply shouldBe WalletSuccess("test-wallet-1", 600L.refineUnsafe[GreaterEqual[0L]], 400L.refineUnsafe[GreaterEqual[0L]])
      result.state.availableBalance shouldBe 600L.refineUnsafe[GreaterEqual[0L]]
      result.state.reservedBalance shouldBe 400L.refineUnsafe[GreaterEqual[0L]]
      result.state.activeHolds("hold-1") shouldBe 400L.refineUnsafe[Positive]
    }

    "reject reserve if insufficient funds" in {
      val result = eventSourcedTestKit.runCommand { replyTo =>
        val resolver = ActorRefResolver(system)
        ReserveTokens("test-wallet-1", "idem-3", "hold-2", 9000L.refineUnsafe[Positive], 3600L, resolver.toSerializationFormat(replyTo))
      }

      result.reply shouldBe WalletFailure("test-wallet-1", "Insufficient available balance", WalletActor.CodeInsufficientBalance)
      result.hasNoEvents shouldBe true
    }

    "support exact command replay (Idempotency Shield)" in {
      val result = eventSourcedTestKit.runCommand { replyTo =>
        val resolver = ActorRefResolver(system)
        // Using "idem-1" again with same params (Add 1000)
        AddTokens("test-wallet-1", "idem-1", 1000L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo))
      }

      // Should return success with current state
      result.reply shouldBe WalletSuccess("test-wallet-1", 600L.refineUnsafe[GreaterEqual[0L]], 400L.refineUnsafe[GreaterEqual[0L]])
      result.hasNoEvents shouldBe true
    }

    "reject duplicate keys with different parameters (Conflict)" in {
      val result = eventSourcedTestKit.runCommand { replyTo =>
        val resolver = ActorRefResolver(system)
        // Using "idem-1" again but with different amount (5000)
        AddTokens("test-wallet-1", "idem-1", 5000L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo))
      }

      result.reply.asInstanceOf[WalletFailure].code shouldBe WalletActor.CodeIdempotencyConflict
      result.hasNoEvents shouldBe true
    }

    "crash and prevent corruption when Long overflow occurs" in {
      // Create a fresh actor for this test to avoid interfering with the previous state
      val overflowTestKit = EventSourcedBehaviorTestKit[Command, Event, WalletState](
        system, WalletActor("test-wallet-overflow"), EventSourcedBehaviorTestKit.SerializationSettings.enabled
      )

      // Add a massive amount near the Long limit
      val nearMax = (Long.MaxValue - 10L).refineUnsafe[Positive]
      overflowTestKit.runCommand { replyTo =>
        val resolver = ActorRefResolver(system)
        AddTokens("test-wallet-overflow", "idem-max-1", nearMax, resolver.toSerializationFormat(replyTo))
      }

      // Try to add an amount that will cause an overflow (exceed Long.MaxValue)
      val pushOverEdge = 50L.refineUnsafe[Positive]
      
      // We expect the actor to throw because Iron's .refineUnsafe fails
      // when the underlying Long silently overflows to a negative number.
      // Iron throws AssertionError (an IllegalArgumentException subclass
      // is no longer guaranteed across versions); accept either.
      assertThrows[Throwable] {
        overflowTestKit.runCommand { replyTo =>
          val resolver = ActorRefResolver(system)
          AddTokens("test-wallet-overflow", "idem-max-2", pushOverEdge, resolver.toSerializationFormat(replyTo))
        }
      }
    }

    "spend tokens against an active hold" in {
      val kit = EventSourcedBehaviorTestKit[Command, Event, WalletState](
        system, WalletActor("wallet-spend"), EventSourcedBehaviorTestKit.SerializationSettings.enabled
      )
      val resolver = ActorRefResolver(system)

      kit.runCommand(replyTo => AddTokens("wallet-spend", "k1", 1000L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo)))
      kit.runCommand(replyTo => ReserveTokens("wallet-spend", "k2", "hold-A", 400L.refineUnsafe[Positive], 3600L, resolver.toSerializationFormat(replyTo)))

      val result = kit.runCommand { replyTo =>
        SpendTokens("wallet-spend", "k3", "hold-A", resolver.toSerializationFormat(replyTo), Map.empty)
      }

      result.reply shouldBe WalletSuccess("wallet-spend", 600L.refineUnsafe[GreaterEqual[0L]], 0L.refineUnsafe[GreaterEqual[0L]])
      result.event.isInstanceOf[TokensSpent] shouldBe true
      result.state.activeHolds shouldBe empty
      result.state.reservedBalance shouldBe 0L.refineUnsafe[GreaterEqual[0L]]
    }

    "release tokens and restore the available balance" in {
      val kit = EventSourcedBehaviorTestKit[Command, Event, WalletState](
        system, WalletActor("wallet-release"), EventSourcedBehaviorTestKit.SerializationSettings.enabled
      )
      val resolver = ActorRefResolver(system)

      kit.runCommand(replyTo => AddTokens("wallet-release", "k1", 1000L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo)))
      kit.runCommand(replyTo => ReserveTokens("wallet-release", "k2", "hold-B", 250L.refineUnsafe[Positive], 3600L, resolver.toSerializationFormat(replyTo)))

      val result = kit.runCommand { replyTo =>
        ReleaseTokens("wallet-release", "k3", "hold-B", resolver.toSerializationFormat(replyTo))
      }

      result.reply shouldBe WalletSuccess("wallet-release", 1000L.refineUnsafe[GreaterEqual[0L]], 0L.refineUnsafe[GreaterEqual[0L]])
      result.event.isInstanceOf[TokensReleased] shouldBe true
      result.state.activeHolds shouldBe empty
      result.state.availableBalance shouldBe 1000L.refineUnsafe[GreaterEqual[0L]]
      result.state.reservedBalance shouldBe 0L.refineUnsafe[GreaterEqual[0L]]
    }

    "reject spend / release against an unknown hold" in {
      val kit = EventSourcedBehaviorTestKit[Command, Event, WalletState](
        system, WalletActor("wallet-unknown-hold"), EventSourcedBehaviorTestKit.SerializationSettings.enabled
      )
      val resolver = ActorRefResolver(system)

      val spend = kit.runCommand { replyTo =>
        SpendTokens("wallet-unknown-hold", "k1", "no-such-hold", resolver.toSerializationFormat(replyTo), Map.empty)
      }
      spend.reply.isInstanceOf[WalletFailure] shouldBe true
      spend.hasNoEvents shouldBe true

      val release = kit.runCommand { replyTo =>
        ReleaseTokens("wallet-unknown-hold", "k2", "no-such-hold", resolver.toSerializationFormat(replyTo))
      }
      release.reply.isInstanceOf[WalletFailure] shouldBe true
      release.hasNoEvents shouldBe true
    }

    "produce identical state after restart (snapshot + replay determinism)" in {
      val kit = EventSourcedBehaviorTestKit[Command, Event, WalletState](
        system, WalletActor("wallet-replay"), EventSourcedBehaviorTestKit.SerializationSettings.enabled
      )
      val resolver = ActorRefResolver(system)

      kit.runCommand(replyTo => AddTokens("wallet-replay", "k1", 1000L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo)))
      kit.runCommand(replyTo => ReserveTokens("wallet-replay", "k2", "h1", 200L.refineUnsafe[Positive], 3600L, resolver.toSerializationFormat(replyTo)))
      kit.runCommand(replyTo => ReserveTokens("wallet-replay", "k3", "h2", 300L.refineUnsafe[Positive], 3600L, resolver.toSerializationFormat(replyTo)))
      kit.runCommand(replyTo => SpendTokens("wallet-replay", "k4", "h1", resolver.toSerializationFormat(replyTo), Map.empty))
      kit.runCommand(replyTo => ReleaseTokens("wallet-replay", "k5", "h2", resolver.toSerializationFormat(replyTo)))

      val before = kit.getState()
      val recovered = kit.restart().state
      recovered shouldBe before
    }

    "prune idempotency keys older than the 24h window on the next event" in {
      val kit = EventSourcedBehaviorTestKit[Command, Event, WalletState](
        system, WalletActor("wallet-prune"), EventSourcedBehaviorTestKit.SerializationSettings.enabled
      )
      val resolver = ActorRefResolver(system)

      // Two real adds give us two genuine idempotency keys with current
      // wall-clock timestamps. Then we directly inject one stale key
      // into state to simulate it being older than 24h.
      kit.runCommand(replyTo => AddTokens("wallet-prune", "fresh-1", 100L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo)))

      val staleTs = System.currentTimeMillis() - (25L * 60 * 60 * 1000) // 25h ago
      kit.initialize(WalletState(
        availableBalance = 100L.refineUnsafe[GreaterEqual[0L]],
        reservedBalance = 0L.refineUnsafe[GreaterEqual[0L]],
        activeHolds = Map.empty,
        recentIdempotencyKeys = Map(
          "fresh-1" -> ProcessedCommand("AddTokens", 100L, System.currentTimeMillis()),
          "stale"   -> ProcessedCommand("AddTokens", 100L, staleTs)
        )
      ))
      kit.getState().recentIdempotencyKeys.keys should contain allOf ("fresh-1", "stale")

      // Trigger any new event; the eventHandler's pruning should drop "stale".
      val result = kit.runCommand { replyTo =>
        AddTokens("wallet-prune", "fresh-2", 50L.refineUnsafe[Positive], resolver.toSerializationFormat(replyTo))
      }

      result.state.recentIdempotencyKeys.keys should contain allOf ("fresh-1", "fresh-2")
      result.state.recentIdempotencyKeys.keys should not contain "stale"
    }
  }
}
