/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ Json, OFormat }

case class MessageBody(
  `type`: Option[String],
  form: Option[String],
  suppressedAt: Option[String],
  detailsId: Option[String]
)

object MessageBody {
  implicit val format: OFormat[MessageBody] = Json.format[MessageBody]
}
