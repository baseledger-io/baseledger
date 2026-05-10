package features

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.jsoniter.*

trait BaseEndpoint:
  def baseEndpoint: Endpoint[Unit, Unit, (StatusCode, ApiError), Unit, Any] =
    endpoint
      .in("api" / "v1")
      .errorOut(statusCode.and(jsonBody[ApiError]))
