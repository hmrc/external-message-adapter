/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.utils

import java.time.format.DateTimeFormatter
import java.time.{ Instant, LocalDate, ZoneOffset }

trait TimeSource {
  def now(): Instant
  def today(): LocalDate = now().atZone(ZoneOffset.UTC).toLocalDate
}

trait SystemTimeSource extends TimeSource {
  val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  override def now(): Instant = Instant.parse(dateTimeFormatter.format(Instant.now))
}

object SystemTimeSource extends SystemTimeSource
