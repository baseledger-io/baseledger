package features.wallet.read

import scala.concurrent.ExecutionContext

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ ActorSystem, Behavior }
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.slick.{ SlickHandler, SlickProjection }
import org.apache.pekko.projection.{ Projection, ProjectionBehavior, ProjectionId }

import domain.{WalletActor, WalletProtocol}
import slick.basic.DatabaseConfig
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile

class WalletHandler(repo: WalletRepository)(using ec: ExecutionContext)
    extends SlickHandler[EventEnvelope[WalletProtocol.Event]]:

  override def process(envelope: EventEnvelope[WalletProtocol.Event]): DBIO[Done] =
    envelope.eventOption match
      case Some(event) => repo.handleEvent(event).map(_ => Done)
      case None        => DBIO.successful(Done)

object WalletProjection:
  def createBehavior(system: ActorSystem[?], repo: WalletRepository): Behavior[ProjectionBehavior.Command] = {
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

    val dbConfig = DatabaseConfig.forConfig[PostgresProfile]("pekko.projection.slick", system.settings.config)

    val projection: Projection[EventEnvelope[WalletProtocol.Event]] = SlickProjection.atLeastOnce(
      projectionId = ProjectionId("wallet-projection", "wallet-1"),
      sourceProvider = sourceProvider,
      databaseConfig = dbConfig,
      handler = () => new WalletHandler(repo)
    )

    ProjectionBehavior[EventEnvelope[WalletProtocol.Event]](projection)
  }
