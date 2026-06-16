/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ Json, OFormat, Reads }

case class Enrolment(
  service: String,
  identifiers: Seq[EnrolmentIdentifier],
  state: Option[String] = None,
  friendlyName: Option[String] = None,
  enrolmentTokenExpiryDate: Option[String] = None
) {

  def isActivated: Boolean = state.contains("Activated")

}

case class EnrolmentIdentifier(key: String, value: String)

object EnrolmentIdentifier {
  implicit val format: OFormat[EnrolmentIdentifier] = Json.format[EnrolmentIdentifier]
}

object Enrolment {
  implicit val enrolmentFormat: OFormat[Enrolment] = Json.format[Enrolment]
}
