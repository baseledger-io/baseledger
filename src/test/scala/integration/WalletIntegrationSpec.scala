package integration

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.util.ByteString

import app.RootGuardian.RootCommand
import app.{ Migrations, RootGuardian }
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end smoke test covering the full single-node stack:
 *
 *   - Flyway migrations against a Testcontainers Postgres
 *   - Cluster sharding (single-node), event-sourced WalletActor
 *   - Hold-expiration projection writing to `hold_expirations`
 *   - Hold-expiration dispatcher polling and issuing `ReleaseTokens`
 *   - HTTP endpoints for add / reserve
 *
 * Reserve tokens with a short TTL, wait past the TTL plus the dispatcher
 * poll interval, then prove the reservation has been auto-released by
 * successfully reserving the full original amount under a fresh hold id.
 */
class WalletIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with Eventually
    with TestContainerForAll {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(30.seconds), interval = scaled(100.millis))

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:18-alpine"),
    databaseName = "wallet_it",
    username = "wallet_it",
    password = "wallet_it"
  )

  // Initialized once the container is up so we can read its mapped port.
  private val testKitRef = new AtomicReference[Option[ActorTestKit]](None)
  private val httpPortRef = new AtomicReference[Int](0)

  private def buildConfig(pg: PostgreSQLContainer, http: Int, remote: Int): Config = {
    val jdbcUrl = pg.jdbcUrl
    ConfigFactory.parseString(
      s"""
         |DB_HOST = "${pg.host}"
         |DB_PORT = ${pg.firstMappedPort}
         |DB_NAME = "${pg.databaseName}"
         |DB_USER = "${pg.username}"
         |DB_PASSWORD = "${pg.password}"
         |
         |pekko.projection.slick.db.url = "$jdbcUrl"
         |pekko.projection.slick.db.user = "${pg.username}"
         |pekko.projection.slick.db.password = "${pg.password}"
         |
         |pekko.persistence.r2dbc.dialect = "postgres"
         |pekko.persistence.r2dbc.connection-factory.driver = "postgres"
         |pekko.persistence.r2dbc.connection-factory.host = "${pg.host}"
         |pekko.persistence.r2dbc.connection-factory.port = ${pg.firstMappedPort}
         |pekko.persistence.r2dbc.connection-factory.database = "${pg.databaseName}"
         |pekko.persistence.r2dbc.connection-factory.user = "${pg.username}"
         |pekko.persistence.r2dbc.connection-factory.password = "${pg.password}"
         |
         |http.port = $http
         |http.host = "127.0.0.1"
         |
         |pekko.cluster.seed-nodes = ["pekko://baseledger-it@127.0.0.1:$remote"]
         |pekko.remote.artery.canonical.port = $remote
         |pekko.remote.artery.canonical.hostname = "127.0.0.1"
         |
         |pekko.projection.slick.profile = "slick.jdbc.PostgresProfile$$"
         |pekko.projection.slick.db.numThreads = 4
         |pekko.projection.slick.db.maxConnections = 4
         |""".stripMargin
    ).withFallback(ConfigFactory.load()).resolve()
  }

  override def afterContainersStart(containers: Containers): Unit = {
    super.afterContainersStart(containers)
    val pg = containers
    val http = freePort()
    val remotePort = freePort()
    httpPortRef.set(http)
    val cfg = buildConfig(pg, http, remotePort)

    // Run migrations BEFORE the actor system starts
    import org.flywaydb.core.Flyway
    Flyway.configure()
      .dataSource(pg.jdbcUrl, pg.username, pg.password)
      .locations("classpath:db/migrations")
      .load()
      .migrate()

    testKitRef.set(Some(ActorTestKit("baseledger-it", cfg)))
  }

  override def beforeContainersStop(containers: Containers): Unit = {
    testKitRef.get().foreach(_.shutdownTestKit())
    super.beforeContainersStop(containers)
  }

  private def freePort(): Int = {
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }

  "The application end-to-end" should {

    "boot, accept HTTP, and auto-release a reservation after its TTL via the hold-expiration dispatcher" in {
      val testKit = testKitRef.get().getOrElse(fail("ActorTestKit was not initialized"))
      val guardian = testKit.spawn(RootGuardian(), "ITGuardian")
      guardian ! RootCommand.Start

      given classicSystem: org.apache.pekko.actor.ActorSystem = testKit.system.classicSystem
      import classicSystem.dispatcher

      val baseUrl = s"http://127.0.0.1:${httpPortRef.get()}"

      // 1. Wait for the server to be live + ready (DB probe).
      eventually(timeout(30.seconds), interval(500.millis)) {
        val resp = Http().singleRequest(HttpRequest(uri = s"$baseUrl/health/ready")).futureValue
        resp.status shouldBe StatusCodes.OK
        resp.discardEntityBytes()
      }

      val walletId = "wallet-it-1"
      val holdId = "hold-it-1"

      // 2. Add 1000.
      postJson(s"$baseUrl/wallet/$walletId/add",
        """{"idempotencyKey":"add-1","amount":1000}""").status shouldBe StatusCodes.OK

      // Verify read-side catch-up for Add
      eventually(timeout(10.seconds), interval(500.millis)) {
        val resp = Http().singleRequest(HttpRequest(uri = s"$baseUrl/wallet/$walletId")).futureValue
        resp.status shouldBe StatusCodes.OK
        val body = Unmarshal(resp.entity).to[String].futureValue
        body should include("\"availableBalance\":1000")
        body should include("\"reservedBalance\":0")
      }

      // 3. Reserve 400 with a 2-second TTL.
      postJson(s"$baseUrl/wallet/$walletId/reserve",
        s"""{"idempotencyKey":"res-1","holdId":"$holdId","amount":400,"ttlSeconds":2}"""
      ).status shouldBe StatusCodes.OK

      // Verify read-side catch-up for Reserve
      eventually(timeout(10.seconds), interval(500.millis)) {
        val resp = Http().singleRequest(HttpRequest(uri = s"$baseUrl/wallet/$walletId")).futureValue
        resp.status shouldBe StatusCodes.OK
        val body = Unmarshal(resp.entity).to[String].futureValue
        body should include("\"availableBalance\":600")
        body should include("\"reservedBalance\":400")
      }

      // 4. Wait for: TTL expires (2 s) + projection commits the row +
      //    dispatcher next poll (up to 5 s) + ReleaseTokens applied.
      //    A fresh reserve of the full 1000 succeeds only after the
      //    original 400 has been auto-released.
      eventually(timeout(45.seconds), interval(1.second)) {
        val probeHold = s"probe-${System.nanoTime}"
        val probeKey = s"probe-${System.nanoTime}"
        val resp = postJson(s"$baseUrl/wallet/$walletId/reserve",
          s"""{"idempotencyKey":"$probeKey","holdId":"$probeHold","amount":1000,"ttlSeconds":3600}""")
        resp.status shouldBe StatusCodes.OK
        val body = Unmarshal(resp.entity).to[String].futureValue
        body should include("\"availableBalance\":0")
        body should include("\"reservedBalance\":1000")
      }
    }
  }

  private def postJson(url: String, json: String)(using sys: org.apache.pekko.actor.ActorSystem): HttpResponse = {
    val req = HttpRequest(
      method = HttpMethods.POST,
      uri = url,
      entity = HttpEntity(ContentTypes.`application/json`, ByteString(json))
    )
    Http().singleRequest(req).futureValue
  }
}
