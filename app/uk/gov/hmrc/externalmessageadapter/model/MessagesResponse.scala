/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import java.time.{ Instant, LocalDate }
import org.mongodb.scala.bson.ObjectId
import play.api.libs.json._
import uk.gov.hmrc.common.message.model.TaxpayerName
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats

final case class MessageListItem(
  id: ObjectId,
  subject: String,
  taxpayerName: Option[TaxpayerName],
  validFrom: LocalDate,
  issueDate: Option[LocalDate],
  readTime: Option[Instant],
  replyTo: Option[String] = None,
  sentInError: Boolean,
  messageType: Option[String] = None,
  counter: Option[Int] = None
)

object MessageListItem {
  implicit val objectIdFormats: Format[ObjectId] = MongoFormats.objectIdFormat
  implicit val messageListItemWrites: Writes[MessageListItem] = Json.writes[MessageListItem]
}

final case class ErrorResponse(reason: String)

object ErrorResponse {
  implicit val format: Format[ErrorResponse] = Json.format[ErrorResponse]
  implicit val reads: Reads[ErrorResponse] = Json.reads[ErrorResponse]
  implicit val writes: Writes[ErrorResponse] = Json.writes[ErrorResponse]
}
