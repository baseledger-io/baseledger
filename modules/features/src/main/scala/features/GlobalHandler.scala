package features

import scala.concurrent.Future

import features.DomainError.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.interceptor.decodefailure.DecodeFailureHandler
import sttp.tapir.server.interceptor.exception.ExceptionHandler
import sttp.tapir.server.model.ValuedEndpointOutput

object GlobalHandler:

  val exceptionHandler: ExceptionHandler[Future] = ExceptionHandler.pure[Future] { ctx =>
    val (code, body) = ctx.e match
      // Case 1: Handle specific Domain Enum
      case err: DomainError =>
        val (code, msg) = err match
          case NotFound(_, _) => (StatusCode.NotFound, err.getMessage)
          case InvalidOperation(_) => (StatusCode.BadRequest, err.getMessage)
          case Unauthorized(_) => (StatusCode.Unauthorized, err.getMessage)

        (code, ApiError("Domain Error", List(msg)))

      case err: (IllegalArgumentException | NoSuchFieldException) =>
        val errorMsg = extractEnumError(err.getMessage)
        (StatusCode.BadRequest, ApiError("Bad Request", List(errorMsg)))

      // Case 2: Handle generic / unexpected failures
      case _ =>
        (StatusCode.InternalServerError, ApiError("Internal Server Error", List("An unexpected error occurred.")))

    Some(ValuedEndpointOutput(statusCode.and(jsonBody[ApiError]), (code, body)))
  }

  val decodeFailureHandler: DecodeFailureHandler[Future] =
    DecodeFailureHandler.pure[Future] { ctx =>
      // Special case: Silently ignore trailing slash mismatches
      ctx.failure match
        case m: DecodeResult.Mismatch if m.actual == "/" && m.expected.isEmpty =>
          None
        case other =>
          val response = other match
            case DecodeResult.Error(_, e: IllegalArgumentException) =>
              val errorMessage = extractErrorMessage(e.getMessage)
              ApiError("Bad Request", errorMessage)

            case e: DecodeResult.Error =>
              val errorMessage = extractErrorMessage(e.error.getMessage)
              ApiError("Bad Request", errorMessage)

            case DecodeResult.InvalidValue(errors) =>
              ApiError("Bad Request", errors.map(_.toString).toList)

            case DecodeResult.Missing =>
              ApiError("Bad Request", List("Required field is missing"))

            case DecodeResult.Mismatch(expected, actual) =>
              ApiError("Bad Request", List(s"Path mismatch: expected '$expected', got '$actual'"))

            case DecodeResult.Multiple(failures) =>
              val messages = failures.flatMap:
                case DecodeResult.Error(_, e) => List(e.getMessage)
                case DecodeResult.InvalidValue(errs) => errs.map(_.toString)
                case _ => List("Validation failed")
              ApiError("Bad Request", messages)

          Some(ValuedEndpointOutput(
            statusCode.and(jsonBody[ApiError]),
            (StatusCode.BadRequest, response)
          ))
    }

  private def extractErrorMessage(message: String): List[String] =
    if message.contains(";") then
      message.replace("requirement failed: ", "").split(";").map(_.trim).filter(_.nonEmpty).toList
    else if message.contains("\n") then
      message.replace("requirement failed: ", "").split("\n").map(_.trim).filter(_.nonEmpty).toList
    else
      List(message.replace("requirement failed: ", "").trim)

  private def extractEnumError(message: String): String =
    val pattern = """.*has no case with name: (.+)""".r
    message match
      case pattern(invalidValue) =>
        s"Invalid value '$invalidValue' for enum."
      case _ =>
        s"Invalid enum value: $message"
