/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter

import uk.gov.hmrc.domain.{ Nino, NinoGenerator, SaUtr, SaUtrGenerator }

import java.util.UUID
import scala.util.Random

object GenerateRandom {
  val rand = new Random()
  val ninoGenerator = new NinoGenerator(rand)
  val utrGenerator = new SaUtrGenerator(rand)

  def email(): String = s"${UUID.randomUUID()}@TEST.com"
  def utr(): SaUtr = utrGenerator.nextSaUtr
  def nino(): Nino = ninoGenerator.nextNino

}
