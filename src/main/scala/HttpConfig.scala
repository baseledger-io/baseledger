package app

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import features.GlobalHandler
import io.opentelemetry.api.OpenTelemetry
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.metrics.opentelemetry.OpenTelemetryMetrics
import sttp.tapir.server.pekkohttp.{ PekkoHttpServerInterpreter, PekkoHttpServerOptions }
import sttp.tapir.server.tracing.opentelemetry.OpenTelemetryTracing
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter

def configureHttp(endpoints: List[ServerEndpoint[Any, Future]], otel: OpenTelemetry)(using system: ActorSystem[?]): Route =
  given ExecutionContext = system.executionContext

  val title = "AI Ledger Engine API"
  val version = "0.1.0"

  val swaggerUIOptions = SwaggerUIOptions.default
  val swaggerEndpoints = SwaggerInterpreter(swaggerUIOptions = swaggerUIOptions)
    .fromServerEndpoints[Future](endpoints, title, version)

  val allEndpoints = endpoints ++ swaggerEndpoints

  val logReceive = (str: String) => {
    system.log.info(s"Request received: $str")
    Future.successful(())
  }

  val logHandled: (String, Option[Throwable]) => Future[Unit] = (msg: String, err: Option[Throwable]) => {
    system.log.info(s"Request handled: $msg")
    Future.successful(())
  }

  val logDecodeFailure: (String, Option[Throwable]) => Future[Unit] = (msg: String, err: Option[Throwable]) => {
    system.log.warn(s"Decode failure: $msg\n${err.map(_.getMessage).getOrElse("")}")
    Future.successful(())
  }

  val logExceptions: (String, Throwable) => Future[Unit] = (msg: String, err: Throwable) => {
    system.log.error(s"Exception: $msg\n${err.getMessage}")
    Future.successful(())
  }

  val openTelemetryMetrics = OpenTelemetryMetrics.default[Future](otel)

  val serverOptions: PekkoHttpServerOptions =
    PekkoHttpServerOptions
      .customiseInterceptors
      .decodeFailureHandler(GlobalHandler.decodeFailureHandler)
      .exceptionHandler(GlobalHandler.exceptionHandler)
      .metricsInterceptor(openTelemetryMetrics.metricsInterceptor())
      .addInterceptor(OpenTelemetryTracing[Future](otel))
      .serverLog(
        DefaultServerLog(
          doLogWhenReceived = logReceive,
          doLogWhenHandled = logHandled,
          doLogAllDecodeFailures = logDecodeFailure,
          doLogExceptions = logExceptions,
          noLog = Future.successful(()),
          logWhenReceived = false,
          logWhenHandled = false,
          logAllDecodeFailures = true,
          logLogicExceptions = true
        ))
      .rejectHandler(None)
      .options

  val securityHeaders: Seq[RawHeader] = List(
    RawHeader("X-Frame-Options", "DENY")
  )

  respondWithHeaders(securityHeaders) {
    PekkoHttpServerInterpreter(serverOptions).toRoute(allEndpoints)
  }
