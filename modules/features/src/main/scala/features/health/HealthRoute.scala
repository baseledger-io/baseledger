package features.health

import scala.concurrent.{ ExecutionContext, Future }

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import features.persistence.R2dbcSessionProvider

class HealthRoute(provider: R2dbcSessionProvider)(using ec: ExecutionContext) {

  private val liveEndpoint: ServerEndpoint[Any, Future] = endpoint.get
    .in("health")
    .in("live")
    .out(stringBody)
    .serverLogicSuccess(_ => Future.successful("OK"))

  private val readyEndpoint: ServerEndpoint[Any, Future] = endpoint.get
    .in("health")
    .in("ready")
    .out(statusCode)
    .serverLogic { _ =>
      provider
        .withSession { session =>
          session.updateOne(session.createStatement("SELECT 1"))
        }
        .map(_ => Right(StatusCode.Ok))
        .recover { case _ => Left(StatusCode.ServiceUnavailable) }
    }

  val endpoints: List[ServerEndpoint[Any, Future]] = List(liveEndpoint, readyEndpoint)
}
