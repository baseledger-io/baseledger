package features

import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.jsoniter._

trait BaseEndpoint:
  def baseEndpoint: Endpoint[Unit, Unit, (StatusCode, ApiError), Unit, Any] =
    endpoint
      .in("api" / "v1")
      .errorOut(statusCode.and(jsonBody[ApiError]))
