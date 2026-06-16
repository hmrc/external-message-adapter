/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ Format, Json }
import uk.gov.hmrc.externalmessageadapter.model.EventHubEvent.MESSAGE_ID_KEY

import java.time.LocalDateTime

case class EventHubEvent(
  eventId: String,
  timestamp: LocalDateTime,
  event: EventBody
)

case class EventBody(
  event: String,
  emailAddress: String,
  detected: LocalDateTime,
  code: Int,
  reason: String,
  tags: Map[String, String]
) {
  import EventHubEvent.eventMapping
  def getEventType: EventType = eventMapping.getOrElse(event.toLowerCase, UnhandledEvent)
  def getMessageId: Option[String] = tags.get(MESSAGE_ID_KEY)
}

object EventHubEvent {

  implicit val eventHubFormat: Format[EventBody] =
    Json.format[EventBody]

  implicit val formats: Format[EventHubEvent] =
    Json.format[EventHubEvent]

  val MESSAGE_ID_KEY = "messageId"
  val eventMapping: Map[String, EventType] = Map(
    "permanentbounce" -> BounceEvent,
    "temporarybounce" -> BounceEvent,
    "rejected"        -> BounceEvent,
    "delivered"       -> DeliveredEvent
  )

}

sealed trait EventType
case object BounceEvent extends EventType
case object DeliveredEvent extends EventType
case object UnhandledEvent extends EventType
