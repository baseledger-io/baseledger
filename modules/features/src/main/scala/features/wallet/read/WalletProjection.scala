package features.wallet.read

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.DispatcherSelector.fromConfig
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.{ Projection, ProjectionBehavior, ProjectionId }

import domain.WalletProtocol

/** Pekko-projection wiring for the wallet read model. */
object WalletProjection:

  /** Projection name; the per-instance offset key is derived from its slice range. */
  private val ProjectionName = "wallet-projection"

  /**
   * Run the wallet read-model projection as a [[ShardedDaemonProcess]] of
   * `number-of-instances` workers, each owning a contiguous slice range produced
   * by the journal's own `sliceRanges` (no hand-rolled partitioning). Pekko keeps
   * every worker alive (restart on failure) and, in a multi-node cluster,
   * distributes them across nodes automatically.
   *
   * The instance count comes from `wallet-projection.number-of-instances`
   * (env `WALLET_PROJECTION_INSTANCES`). Changing it needs only a restart, never
   * a rebuild: the r2dbc offset store resumes per-slice (`WHERE slice BETWEEN
   * minSlice AND maxSlice`), so re-partitioning the ranges continues from the
   * committed offsets without replaying the whole journal.
   */
  def init(system: ActorSystem[?]): Unit =
    val numberOfInstances =
      math.max(1, system.settings.config.getInt("wallet-projection.number-of-instances"))

    ShardedDaemonProcess(system).init(
      name = ProjectionName,
      numberOfInstances = numberOfInstances,
      behaviorFactory = index => ProjectionBehavior(createProjection(system, numberOfInstances, index)),
      stopMessage = ProjectionBehavior.Stop
    )

  /** Build the projection owning the slice range for `index` of `numberOfInstances`. */
  private def createProjection(
      system: ActorSystem[?],
      numberOfInstances: Int,
      index: Int
  ): Projection[EventEnvelope[WalletProtocol.Event]] =
    given ActorSystem[?] = system
    given ExecutionContext = system.dispatchers.lookup(fromConfig("projection-dispatcher"))

    val range = EventSourcedProvider
      .sliceRanges(system, R2dbcReadJournal.Identifier, numberOfInstances)(index)
    val minSlice = range.min
    val maxSlice = range.max

    val sourceProvider = EventSourcedProvider
      .eventsBySlices[WalletProtocol.Event](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        entityType = domain.WalletActor.TypeKey.name,
        minSlice = minSlice,
        maxSlice = maxSlice
      )

    R2dbcProjection
      .groupedWithin(
        projectionId = ProjectionId(ProjectionName, s"wallet-$minSlice-$maxSlice"),
        settings = None,
        sourceProvider = sourceProvider,
        handler = () => new WalletProjectionHandler()
      )
      .withGroup(groupAfterEnvelopes = 20, groupAfterDuration = 500.millis)
