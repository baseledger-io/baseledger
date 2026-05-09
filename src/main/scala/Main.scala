import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.apache.pekko.actor.typed.ActorSystem

import app.{ Migrations, RootGuardian }
import com.typesafe.config.ConfigFactory
import features.wallet.expiration.HoldExpirationDispatcher

object Main:
  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()

    Migrations.run(config)

    val system = ActorSystem(RootGuardian(), "baseledger", config)

    system.whenTerminated.foreach(_ => println("BaseLedger shut down successfully."))(system.executionContext)

    Await.result(system.whenTerminated, Duration.Inf)
