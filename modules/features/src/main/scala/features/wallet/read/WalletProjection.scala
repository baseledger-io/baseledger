package features.wallet.read

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.typed.{ ActorSystem, Behavior }
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.{ Projection, ProjectionBehavior, ProjectionId }

import domain.WalletProtocol

/** Pekko-projection wiring for the wallet read model. */
object WalletProjection:
  def createBehavior(system: ActorSystem[?]): Behavior[ProjectionBehavior.Command] =
    given ActorSystem[?] = system
    given ExecutionContext = system.executionContext

    val sourceProvider = EventSourcedProvider
      .eventsBySlices[domain.WalletProtocol.Event](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        entityType = domain.WalletActor.TypeKey.name,
        minSlice = 0,
        maxSlice = 1023
      )

    val projection: Projection[EventEnvelope[WalletProtocol.Event]] =
      R2dbcProjection.exactlyOnce(
        projectionId = ProjectionId("wallet-projection", "wallet-1"),
        settings = None,
        sourceProvider = sourceProvider,
        handler = () => new WalletProjectionHandler()
      )

    ProjectionBehavior[EventEnvelope[WalletProtocol.Event]](projection)
