/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import java.time.{ Instant, LocalDate }
import org.mongodb.scala.bson.ObjectId
import play.api.libs.json._
import uk.gov.hmrc.common.message.model.{ Details, MessageContentParameters }

final case class ServiceUrl(service: String, url: String)

object ServiceUrl {

  implicit val fmt: Format[ServiceUrl] = Json.format[ServiceUrl]

}

final case class MessageResourceResponse(
  id: ObjectId,
  subject: String,
  body: Option[Details],
  validFrom: LocalDate,
  readTime: Either[ServiceUrl, Instant],
  contentParameters: Option[MessageContentParameters],
  sentInError: Boolean,
  renderUrl: ServiceUrl
)
