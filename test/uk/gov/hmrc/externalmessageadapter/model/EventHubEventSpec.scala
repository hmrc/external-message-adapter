/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_EMAIL_ADDRESS_VALUE, TEST_EVENT, TEST_ID, TEST_LOCAL_DATE_TIME, TEST_REASON }

class EventHubEventSpec extends SpecBase {

  "EventHubEvent.formats" should {
    import EventHubEvent.formats

    "read the json correctly" in new Setup {
      Json.parse(eventHubEventJsonString).as[EventHubEvent] mustBe eventHubEvent
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(eventHubEventInvalidJsonString).as[EventHubEvent]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(eventHubEvent) mustBe Json.parse(eventHubEventJsonString)
    }
  }

  "EventHubEvent.eventMapping" should {
    "return correct map of event types" in {

      EventHubEvent.eventMapping mustBe Map(
        "permanentbounce" -> BounceEvent,
        "temporarybounce" -> BounceEvent,
        "rejected"        -> BounceEvent,
        "delivered"       -> DeliveredEvent
      )
    }
  }

  trait Setup {
    val eventBody: EventBody =
      EventBody(
        event = TEST_EVENT,
        emailAddress = TEST_EMAIL_ADDRESS_VALUE,
        detected = TEST_LOCAL_DATE_TIME,
        code = 2,
        reason = TEST_REASON,
        tags = Map()
      )

    val eventHubEvent: EventHubEvent =
      EventHubEvent(eventId = TEST_ID, timestamp = TEST_LOCAL_DATE_TIME, event = eventBody)

    val eventHubEventJsonString: String =
      """{
        |"eventId":"test_id",
        |"timestamp":"2026-02-20T01:21:00",
        |"event":{
        |"event":"Event_hub",
        |"emailAddress":"test@test.com",
        |"detected":"2026-02-20T01:21:00",
        |"code":2,
        |"reason":"test_reason",
        |"tags":{}
        |}
        |}""".stripMargin

    val eventHubEventInvalidJsonString: String =
      """{
        |"timestamp":"2026-02-20T01:21:00",
        |"event":{
        |"event":"Event_hub",
        |"emailAddress":"test@test.com",
        |"detected":"2026-02-20T01:21:00",
        |"code":2,
        |"reason":"test_reason",
        |"tags":{}
        |}
        |}""".stripMargin
  }
}
