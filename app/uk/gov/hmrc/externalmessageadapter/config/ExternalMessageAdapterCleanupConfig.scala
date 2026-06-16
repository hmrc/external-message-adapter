/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.config

import play.api.Configuration

import java.time.LocalTime
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.*

@Singleton
class ExternalMessageAdapterCleanupConfig @Inject() (configuration: Configuration) {
  val name = "externalMessageAdapterCleanup"

  lazy val initialDelay: FiniteDuration =
    configuration
      .get[Duration](s"scheduling.$name.initialDelay")
      .toMillis
      .milliseconds

  lazy val interval: FiniteDuration =
    configuration
      .get[Duration](s"scheduling.$name.interval")
      .toMillis
      .milliseconds

  lazy val lockDuration: Option[FiniteDuration] =
    configuration
      .getOptional[Duration](s"scheduling.$name.lockDuration")
      .flatMap(duration => Some(duration.toMillis.milliseconds))

  lazy val releaseLockAfter: Duration =
    lockDuration.getOrElse(Duration("1 hour"))

  lazy val taskEnabled: Boolean =
    configuration.get[Boolean](s"scheduling.$name.taskEnabled")

  lazy val deleteAfter: FiniteDuration =
    configuration
      .getOptional[Duration](s"scheduling.$name.deleteAfter")
      .map(_.toMillis.milliseconds)
      .getOrElse(365.days)

  lazy val batchSize: Int =
    configuration.getOptional[Int](s"scheduling.$name.batchSize").getOrElse(1000)

  lazy val startAt: LocalTime =
    LocalTime.parse(configuration.getOptional[String](s"scheduling.$name.startAt").getOrElse("08:00"))

  lazy val stopAt: LocalTime =
    LocalTime.parse(configuration.getOptional[String](s"scheduling.$name.stopAt").getOrElse("23:00"))

  def isActive: Boolean = {
    val executionTime = LocalTime.now()
    executionTime.isAfter(startAt) && executionTime.isBefore(stopAt)
  }
}
