package features.wallet

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

import org.apache.pekko.actor.typed.{ActorRefResolver, ActorSystem, Scheduler}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import domain.WalletActor
import domain.WalletProtocol._
import domain.WalletTypes._
import domain.wallet._
import features.wallet.read.{ WalletRepository, WalletRow }
import features.{ ApiError, BaseEndpoint }
import io.github.iltotore.iron._
import io.github.iltotore.iron.constraint.numeric._
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.jsoniter._
import sttp.tapir.server.ServerEndpoint

object WalletRoute:
  case class AddTokensRequest(idempotencyKey: String, amount: Long)
  case class ReserveTokensRequest(idempotencyKey: String, holdId: String, amount: Long, ttlSeconds: Long)
  case class SpendTokensRequest(idempotencyKey: String, holdId: String, metadata: Option[Map[String, String]] = None)
  case class ReleaseTokensRequest(idempotencyKey: String, holdId: String)
  
  case class WalletResponseDto(id: String, availableBalance: Long, reservedBalance: Long)

  given JsonValueCodec[AddTokensRequest] = JsonCodecMaker.make
  given JsonValueCodec[ReserveTokensRequest] = JsonCodecMaker.make
  given JsonValueCodec[SpendTokensRequest] = JsonCodecMaker.make
  given JsonValueCodec[ReleaseTokensRequest] = JsonCodecMaker.make
  given JsonValueCodec[WalletResponseDto] = JsonCodecMaker.make

  private val addEndpoint =
    endpoint
      .post
      .in("wallet" / path[String]("id") / "add")
      .in(jsonBody[AddTokensRequest])
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .out(statusCode)
      .out(jsonBody[WalletResponseDto])
      .description("Add tokens to wallet")

  private val reserveEndpoint =
    endpoint
      .post
      .in("wallet" / path[String]("id") / "reserve")
      .in(jsonBody[ReserveTokensRequest])
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .out(statusCode)
      .out(jsonBody[WalletResponseDto])
      .description("Reserve tokens in wallet")

  private val spendEndpoint =
    endpoint
      .post
      .in("wallet" / path[String]("id") / "spend")
      .in(jsonBody[SpendTokensRequest])
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .out(statusCode)
      .out(jsonBody[WalletResponseDto])
      .description("Spend reserved tokens")

  private val releaseEndpoint =
    endpoint
      .post
      .in("wallet" / path[String]("id") / "release")
      .in(jsonBody[ReleaseTokensRequest])
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .out(statusCode)
      .out(jsonBody[WalletResponseDto])
      .description("Release reserved tokens")

  private val getEndpoint =
    endpoint
      .get
      .in("wallet" / path[String]("id"))
      .errorOut(statusCode.and(jsonBody[ApiError]))
      .out(statusCode)
      .out(jsonBody[WalletResponseDto])
      .description("Get wallet balance")

class WalletRoute(sharding: ClusterSharding, repo: WalletRepository)(using system: ActorSystem[?]) extends BaseEndpoint:
  import WalletRoute.*

  given ExecutionContext = system.executionContext
  given Scheduler = system.scheduler
  given Timeout(3.seconds)
  private val resolver = ActorRefResolver(system)

  private val codeToStatus: Map[String, StatusCode] = Map(
    WalletActor.CodeIdempotencyConflict -> StatusCode.Conflict,
    WalletActor.CodeWalletNotFound       -> StatusCode.NotFound,
    WalletActor.CodeHoldNotFound         -> StatusCode.NotFound,
    WalletActor.CodeInsufficientBalance  -> StatusCode.BadRequest,
    WalletActor.CodeHoldAlreadyExists    -> StatusCode.Conflict
  )

  private def handleResponse(id: String, resp: Response): Either[(StatusCode, ApiError), (StatusCode, WalletResponseDto)] =
    resp match
      case WalletSuccess(id, available, reserved, _) =>
        Right((StatusCode.Ok, WalletResponseDto(id, available.asInstanceOf[Long], reserved.asInstanceOf[Long])))

      case WalletFailure(_, reason, code, _) =>
        val status = codeToStatus.getOrElse(code, StatusCode.BadRequest)
        Left((status, ApiError(code, List(reason))))

  val route: List[ServerEndpoint[Any, Future]] =
    List(
      addEndpoint.serverLogic { (id, req) =>
        req.amount.refineEither[Positive] match {
          case Right(validAmount) =>
            sharding.entityRefFor(WalletActor.TypeKey, id)
              .ask[Response](replyTo => AddTokens(id, req.idempotencyKey, validAmount, resolver.toSerializationFormat(replyTo)))
              .map(r => handleResponse(id, r))
          case Left(errorMsg) =>
            Future.successful(Left((StatusCode.BadRequest, ApiError("VALIDATION_ERROR", List(s"Amount must be strictly positive: $errorMsg")))))
        }
      },
      reserveEndpoint.serverLogic { (id, req) =>
        req.amount.refineEither[Positive] match {
          case Right(validAmount) =>
            sharding.entityRefFor(WalletActor.TypeKey, id)
              .ask[Response](replyTo => ReserveTokens(id, req.idempotencyKey, req.holdId, validAmount, req.ttlSeconds, resolver.toSerializationFormat(replyTo)))
              .map(r => handleResponse(id, r))
          case Left(errorMsg) =>
            Future.successful(Left((StatusCode.BadRequest, ApiError("VALIDATION_ERROR", List(s"Amount must be strictly positive: $errorMsg")))))
        }
      },
      spendEndpoint.serverLogic { (id, req) =>
        sharding.entityRefFor(WalletActor.TypeKey, id)
          .ask[Response](replyTo => SpendTokens(id, req.idempotencyKey, req.holdId, resolver.toSerializationFormat(replyTo), req.metadata.getOrElse(Map.empty)))
          .map(r => handleResponse(id, r))
      },
      releaseEndpoint.serverLogic { (id, req) =>
        sharding.entityRefFor(WalletActor.TypeKey, id)
          .ask[Response](replyTo => ReleaseTokens(id, req.idempotencyKey, req.holdId, resolver.toSerializationFormat(replyTo)))
          .map(r => handleResponse(id, r))
      },
      getEndpoint.serverLogic { id =>
        repo.findById(id).map:
          case Some(row) => Right((StatusCode.Ok, WalletResponseDto(row.id, row.availableBalance, row.reservedBalance)))
          case None      => Left((StatusCode.NotFound, ApiError(WalletActor.CodeWalletNotFound, List(s"Wallet $id not found"))))
      }
    )
