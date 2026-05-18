package app

import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route

def startHttpServer(routes: Route, host: String, port: Int)(using system: ActorSystem[?]): Unit =

  system.log.info(s"Attempting to bind HTTP server to $host:$port")

  Http()
    .newServerAt(host, port)
    .bind(routes)
    .onComplete {
      case Success(binding) =>
        binding.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds)
        val hostStr = if binding.localAddress.getHostString == "0:0:0:0:0:0:0:0" || binding.localAddress.getHostString == "0.0.0.0" then "localhost" else binding.localAddress.getHostString
        val baseUrl = s"http://$hostStr:${binding.localAddress.getPort}"
        
        system.log.info(s"Server online at $baseUrl/")
        system.log.info(s" ➔ Swagger API Docs : $baseUrl/docs")
        system.log.info(s" ➔ Liveness Check   : $baseUrl/health/live")
        system.log.info(s" ➔ Readiness Check  : $baseUrl/health/ready")
      case Failure(ex) =>
        system.log.error(s"Server could not start!", ex)
        system.terminate()
    }(system.executionContext)
