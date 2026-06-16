/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.externalmessageadapter.util.TestData.*

class MessagesResponseSpec extends SpecBase {

  "MessageListItem.messageListItemWrites" should {
    import MessageListItem.messageListItemWrites

    "write the object correctly" in new Setup {
      Json.toJson(messageListItem) mustBe Json.parse(messageListItemJsonString)
    }
  }

  "ErrorResponse.format" should {
    import ErrorResponse.format

    "read the json correctly" in new Setup {
      Json.parse(errorResponseJsonString).as[ErrorResponse] mustBe errorResponse
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(errorResponseInvalidJsonString).as[ErrorResponse]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(errorResponse) mustBe Json.parse(errorResponseJsonString)
    }
  }

  "ErrorResponse.reads" should {
    import ErrorResponse.reads

    "read the json correctly" in new Setup {
      Json.parse(errorResponseJsonString).as[ErrorResponse] mustBe errorResponse
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(errorResponseInvalidJsonString).as[ErrorResponse]
      }
    }
  }

  "ErrorResponse.writes" should {
    import ErrorResponse.writes

    "write the object correctly" in new Setup {
      Json.toJson(errorResponse) mustBe Json.parse(errorResponseJsonString)
    }
  }

  trait Setup {
    val messageListItem: MessageListItem = MessageListItem(
      id = TEST_OBJECT_ID,
      subject = TEST_SUBJECT,
      taxpayerName = Some(TEST_TAXPAYER),
      validFrom = TEST_LOCAL_DATE,
      issueDate = Some(TEST_LOCAL_DATE),
      readTime = Some(TEST_TIME_INSTANT),
      replyTo = Some(TEST_EMAIL_ADDRESS_VALUE),
      sentInError = false,
      messageType = Some(TEST_MESSAGE_TYPE),
      counter = Some(TEST_COUNT)
    )

    val messageListItemJsonString: String =
      """{
        |"id":{"$oid":"64c13ab08edf48a008793cac"},
        |"subject":"test_subject",
        |"taxpayerName":{"title":"test_title","forename":"test_name"},
        |"validFrom":"2026-02-20",
        |"issueDate":"2026-02-20",
        |"readTime":"1970-01-01T00:20:34.567Z",
        |"replyTo":"test@test.com",
        |"sentInError":false,
        |"messageType":"test_msg_type",
        |"counter":5
        |}""".stripMargin

    val errorResponse: ErrorResponse = ErrorResponse(TEST_REASON)

    val errorResponseJsonString: String = """{"reason":"test_reason"}""".stripMargin
    val errorResponseInvalidJsonString: String = """{}""".stripMargin
  }
}
