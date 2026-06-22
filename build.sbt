resolvers += "Maven Central".at("https://repo1.maven.org/maven2/")

import sbt.*
import Keys.*

import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.graalvmnativeimage.GraalVMNativeImagePlugin
import com.typesafe.sbt.packager.graalvmnativeimage.GraalVMNativeImagePlugin.autoImport.*

val scala3Version = "3.3.7"
val PekkoVersion = "1.5.0"
val PekkoHttpVersion = "1.3.0"
val PekkoConnectorsVersion = "1.3.0"
val PekkoProjectionVersion = "1.1.0"
val TapirVersion = "1.13.17"
val JsoniterVersion = "2.38.9"

// Global Settings
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "io.baseledger"
ThisBuild / scalaVersion := scala3Version

// Static Analysis: SemanticDB for Scalafix
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalafixOnCompile := false

ThisBuild / assemblyMergeStrategy := {
  case x if x.endsWith("module-info.class") => MergeStrategy.discard
  case x if x.endsWith(".proto") => MergeStrategy.discard
  case x if x.endsWith("io.netty.versions.properties") => MergeStrategy.first
  case PathList("reference.conf") => MergeStrategy.concat
  case PathList("application.conf") => MergeStrategy.concat
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", "native-image", _*) => MergeStrategy.first
  case PathList("META-INF", "versions", _*) => MergeStrategy.first
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", _*) => MergeStrategy.first
  case _ => MergeStrategy.first
}

lazy val common = project
  .in(file("modules/common"))
  .settings(
    // ScalaPB Configuration
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = false) -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= Seq(
      "io.github.iltotore"  %% "iron"                        % "3.3.0",
      "org.apache.pekko"    %% "pekko-slf4j"                 % PekkoVersion,
      "ch.qos.logback"       % "logback-classic"             % "1.5.32",
      "net.logstash.logback" % "logstash-logback-encoder"    % "9.0",
      "org.apache.pekko"    %% "pekko-serialization-jackson" % PekkoVersion
    )
  )

lazy val domain = project
  .in(file("modules/domain"))
  .dependsOn(common)
  .settings(
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime"              % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "org.apache.pekko"     %% "pekko-cluster-sharding-typed" % PekkoVersion,
      "org.apache.pekko"     %% "pekko-persistence-typed"      % PekkoVersion
    ),
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = false) -> (Compile / sourceManaged).value / "scalapb"
    )
  )

lazy val features = project
  .in(file("modules/features"))
  .dependsOn(domain)
  .settings(
    libraryDependencies ++= Seq(
      // Persistence (Drivers only)
      "org.postgresql"    % "postgresql"              % "42.7.10",
      "org.apache.pekko" %% "pekko-persistence-r2dbc" % "1.1.0",
      "org.apache.pekko" %% "pekko-projection-r2dbc"  % "1.1.0",
      "org.postgresql"    % "r2dbc-postgresql"        % "1.1.1.RELEASE",

      // Projections
      "org.apache.pekko" %% "pekko-projection-core"         % PekkoProjectionVersion,
      "org.apache.pekko" %% "pekko-projection-eventsourced" % PekkoProjectionVersion,

      // Unified Observability (OTel)
      "com.softwaremill.sttp.tapir"           %% "tapir-opentelemetry-metrics"               % TapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-swagger-ui-bundle"                   % TapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-opentelemetry-tracing"               % TapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-pekko-http-server"                   % TapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"                      % TapirVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"                       % JsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros"                     % JsoniterVersion % "provided",
      "io.opentelemetry"                       % "opentelemetry-sdk"                         % "1.43.0",
      "io.opentelemetry"                       % "opentelemetry-exporter-otlp"               % "1.43.0",
      "io.opentelemetry"                       % "opentelemetry-sdk-extension-autoconfigure" % "1.43.0",
      "io.opentelemetry.instrumentation"       % "opentelemetry-runtime-telemetry-java8"     % "2.9.0-alpha"
    )
  )

lazy val tests = project
  .in(file("modules/tests"))
  .dependsOn(common, domain, features)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalatest"    %% "scalatest"                 % "3.2.20"               % Test,
      "org.scalacheck"   %% "scalacheck"                % "1.19.0"               % Test,
      "org.apache.pekko" %% "pekko-testkit"             % PekkoVersion           % Test,
      "org.apache.pekko" %% "pekko-stream-testkit"      % PekkoVersion           % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % PekkoVersion           % Test,
      "org.apache.pekko" %% "pekko-projection-testkit"  % PekkoProjectionVersion % Test

    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(common, domain, features, tests)
  .dependsOn(features, tests % "test->test")
  .enablePlugins(GraalVMNativeImagePlugin)
  .settings(
    name                := "baseledger",
    Compile / mainClass := Some("Main"),
    assembly / mainClass := Some("Main"),
    assembly / assemblyJarName := "baseledger.jar",
    GraalVMNativeImagePlugin.autoImport.graalVMNativeImageOptions ++= Seq(
      "--enable-monitoring=jfr,jvmstat,jcmd,threaddump,heapdump,nmt",
      "-H:+PreserveFramePointer",
      "-g",
      "--no-fallback",
      "--install-exit-handlers",
      "--enable-url-protocols=http,https",
      "--enable-native-access=ALL-UNNAMED",
      "--static",
      "--libc=musl",
      "-O3",
      "-J-Xmx6g",
      "--initialize-at-build-time=scala.runtime.Statics$VM",
      "--initialize-at-run-time=io.netty",
      "--initialize-at-run-time=org.slf4j",
      "--initialize-at-run-time=ch.qos.logback",
      "--initialize-at-run-time=org.apache.pekko.event.slf4j",
      "--initialize-at-run-time=org.postgresql",
      "-H:IncludeResources=.*\\.conf",
      "-H:IncludeResources=.*\\.properties",
      "-H:IncludeResources=.*\\.xml",
      "-H:IncludeResources=.*\\.html",
      "-H:IncludeResources=.*\\.js",
      "-H:IncludeResources=.*\\.css",
      "-H:IncludeResources=.*\\.png",
      "-H:IncludeResources=.*\\.svg",
      "-H:IncludeResources=.*\\.json",
      "-H:IncludeResources=db/migrations/.*\\.sql",
      "-H:IncludeResources=META-INF/native-image/.*",
      "-H:IncludeResources=META-INF/resources/webjars/.*",
      "-H:IncludeResources=reference\\.conf",
      "-H:+UnlockExperimentalVMOptions",
      "-H:+ReportExceptionStackTraces"
    ),
    libraryDependencies ++= Seq(
      "org.scalatest"    %% "scalatest"                       % "3.2.20"         % Test,
      "org.apache.pekko" %% "pekko-testkit"                   % PekkoVersion     % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed"       % PekkoVersion     % Test,
      "org.apache.pekko" %% "pekko-http-testkit"              % PekkoHttpVersion % Test,
      "com.dimafeng"     %% "testcontainers-scala-scalatest"  % "0.41.4"         % Test,
      "com.dimafeng"     %% "testcontainers-scala-postgresql" % "0.41.4"         % Test,
      "org.flywaydb"      % "flyway-core"                     % "11.8.2"         % Test,
      "org.flywaydb"      % "flyway-database-postgresql"      % "11.8.2"         % Test
    )
  )
