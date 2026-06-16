/*
 * Copyright 2023 HM Revenue & Customs
 *
 */
import play.sbt.PlayImport.*
import sbt.*

object AppDependencies {

  private val pekkoVersion = "1.0.3"
  private val pekkoHttpVersion = "1.0.1"
  private val bootstrapVersion = "10.7.0"
  private val guiceVersion = "6.0.0"
  private val hmrcMongoVersion = "2.12.0"

  lazy val compile: Seq[ModuleID] = Seq(
    caffeine,
    "uk.gov.hmrc"    %% "bootstrap-backend-play-30" % bootstrapVersion,
    "uk.gov.hmrc"    %% "dc-message-library"        % "1.28.0",
    "net.codingwell" %% "scala-guice"               % guiceVersion,
    "org.jsoup"       % "jsoup"                     % "1.15.4"
  )

  lazy val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % bootstrapVersion % "test",
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-30" % hmrcMongoVersion % "test",
    "org.scalatestplus" %% "mockito-4-11"            % "3.2.17.0"       % "test",
    "net.codingwell"    %% "scala-guice"             % guiceVersion     % "test",
    "uk.gov.hmrc"       %% "domain-test-play-30"     % "13.0.0"         % "test",

    // Pekko Stream testing dependencies
    "org.apache.pekko" %% "pekko-stream-testkit"      % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-testkit"             % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test
  )

  lazy val appDependencies: Seq[ModuleID] = compile ++ test

  val overrides: Seq[ModuleID] = Seq(
    "org.apache.pekko" %% "pekko-stream"                % pekkoVersion,
    "org.apache.pekko" %% "pekko-protobuf"              % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j"                 % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor"                 % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor-typed"           % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http-core"             % pekkoHttpVersion
  )
}
