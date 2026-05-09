package domain

import org.apache.pekko.actor.typed._

import domain.wallet._

object WalletProtocol:
  type Command = AddTokens | ReserveTokens | SpendTokens | ReleaseTokens
  type Event = TokensAdded | TokensReserved | TokensSpent | TokensReleased
  type Response = WalletSuccess | WalletFailure

  extension (cmd: AddTokens)
    def replyToRef(using resolver: ActorRefResolver): ActorRef[Response] =
      resolver.resolveActorRef(cmd.replyTo)

  extension (cmd: ReserveTokens)
    def replyToRef(using resolver: ActorRefResolver): ActorRef[Response] =
      resolver.resolveActorRef(cmd.replyTo)

  extension (cmd: SpendTokens)
    def replyToRef(using resolver: ActorRefResolver): ActorRef[Response] =
      resolver.resolveActorRef(cmd.replyTo)

  extension (cmd: ReleaseTokens)
    def replyToRef(using resolver: ActorRefResolver): ActorRef[Response] =
      resolver.resolveActorRef(cmd.replyTo)
