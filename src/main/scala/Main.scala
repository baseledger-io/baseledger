import org.apache.pekko.actor.typed.ActorSystem

import app.{ Migrations, RootGuardian }
import com.typesafe.config.ConfigFactory

object Main:
  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()

    Migrations.run(config)

    val system = ActorSystem(RootGuardian(), "baseledger", config)

    system.whenTerminated.onComplete {
      case scala.util.Success(_) =>
        println("BaseLedger shut down successfully.")
      case scala.util.Failure(e) =>
        println(s"BaseLedger shut down with error: $e")
    }(system.executionContext)
