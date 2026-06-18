/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

import uk.gov.hmrc.DefaultBuildSettings
import uk.gov.hmrc.DefaultBuildSettings.{ defaultSettings, scalaSettings }

val appName = "external-message-adapter"

ThisBuild / majorVersion := 1
ThisBuild / scalaVersion := "3.3.6"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(scalaSettings *)
  .settings(defaultSettings() *)
  .settings(
    libraryDependencies ++= AppDependencies.appDependencies,
    dependencyOverrides ++= AppDependencies.overrides,
    Test / parallelExecution := false,
    Test / fork := false,
    retrieveManaged := true,
    routesImport ++= Seq(
      "uk.gov.hmrc.externalmessageadapter.controllers.binders.Binders._"
    ),
    routesGenerator := InjectedRoutesGenerator
  )
  .settings(inConfig(TemplateTest)(Defaults.testSettings) *)
  .settings(
    scalacOptions ++= List(
      // Silence unused imports in template files
      "-Wconf:msg=unused import&src=.*:s",
      // Silence "Flag -XXX set repeatedly"
      "-Wconf:msg=Flag.*repeatedly:s",
      // Silence unused warnings on Play `routes` files
      "-Wconf:src=routes/.*:s"
    ),
    scalacOptions := scalacOptions.value.distinct
  )
  .settings(ScoverageSettings())

lazy val TemplateTest = config("tt") extend Test

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())

Test / test := (Test / test)
  .dependsOn(scalafmtCheckAll)
  .value

it / test := (it / Test / test)
  .dependsOn(scalafmtCheckAll, it / scalafmtCheckAll)
  .value
