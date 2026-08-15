package config

import com.typesafe.config.ConfigFactory
import common.TestEnv
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Guards against config-resolution failures that compile cleanly and only surface
 * when the application boots (notably in the native image, where a startup crash
 * costs a full rebuild to diagnose).
 *
 * Regression test for the Typesafe Config `ConfigException$BugOrBroken:
 * replaceChild did not find` that was triggered by duplicate `${?ENV}` override
 * keys inside the `projection-connection-factory` object-concatenation.
 */
final class ConfigResolutionSpec extends AnyWordSpec with Matchers {

  // Seed env-var fallbacks before the first ConfigFactory.load().
  TestEnv.init()

  "application.conf" should {

    "resolve fully without throwing" in {
      ConfigFactory.invalidateCaches()
      noException should be thrownBy ConfigFactory.load().resolve()
    }

    "expose three independent, resolvable R2DBC connection pools" in {
      ConfigFactory.invalidateCaches()
      val config = ConfigFactory.load().resolve()

      // Each pool's max-size substitution must resolve (would throw if unresolved).
      config.getString("pekko.persistence.r2dbc.connection-factory.max-size") shouldBe "256"
      config.getString("read-side-connection-factory.max-size") shouldBe "256"
      config.getString("projection-connection-factory.max-size") shouldBe "256"
      config.getString("projection-connection-factory.initial-size") shouldBe "64"
    }

    "run the projection on its own dedicated factory that inherits read-side params" in {
      ConfigFactory.invalidateCaches()
      val config = ConfigFactory.load().resolve()

      config.getString("pekko.projection.r2dbc.use-connection-factory") shouldBe
        "projection-connection-factory"
      // Inherited from read-side-connection-factory (proves the substitution merge).
      config.getString("projection-connection-factory.driver") shouldBe "postgres"
    }

    "drive the projection instance count from the WALLET_PROJECTION_INSTANCES env var" in {
      ConfigFactory.invalidateCaches()
      val config = ConfigFactory.load().resolve()

      // Required substitution: resolves from the env var (TestEnv seeds "1"),
      // with no hardcoded fallback baked into application.conf.
      config.getInt("wallet-projection.number-of-instances") shouldBe 1
    }
  }
}
