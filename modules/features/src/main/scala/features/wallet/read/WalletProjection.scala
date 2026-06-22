package features.wallet.read

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.apache.pekko.actor.typed.{ ActorSystem, Behavior }
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.{ Projection, ProjectionBehavior, ProjectionId }
import org.apache.pekko.actor.typed.DispatcherSelector.fromConfig

import domain.WalletProtocol

/** Pekko-projection wiring for the wallet read model. */
object WalletProjection:
  def createBehavior(system: ActorSystem[?]): Behavior[ProjectionBehavior.Command] =
    val projectionDispatcher = fromConfig("projection-dispatcher")
    given ActorSystem[?] = system
    given ExecutionContext = system.dispatchers.lookup(projectionDispatcher)

    val sourceProvider = EventSourcedProvider
      .eventsBySlices[domain.WalletProtocol.Event](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        entityType = domain.WalletActor.TypeKey.name,
        minSlice = 0,
        maxSlice = 1023
      )

    val projection: Projection[EventEnvelope[WalletProtocol.Event]] =
      R2dbcProjection.groupedWithin(
        projectionId = ProjectionId("wallet-projection", "wallet-1"),
        settings = None,
        sourceProvider = sourceProvider,
        handler = () => new WalletProjectionHandler()
      ).withGroup(groupAfterEnvelopes = 20, groupAfterDuration = 500.millis)

    ProjectionBehavior[EventEnvelope[WalletProtocol.Event]](projection)
