/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
