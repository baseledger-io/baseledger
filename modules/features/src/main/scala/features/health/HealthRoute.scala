package features.health

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import slick.jdbc.JdbcBackend
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.server.ServerEndpoint

class HealthRoute(db: JdbcBackend.Database)(using ec: ExecutionContext) {

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
      import slick.jdbc.PostgresProfile.api.*
      db.run(sql"SELECT 1".as[Int]).map(_ => Right(StatusCode.Ok)).recover {
        case _ => Left(StatusCode.ServiceUnavailable)
      }
    }

  val endpoints: List[ServerEndpoint[Any, Future]] = List(liveEndpoint, readyEndpoint)
}
