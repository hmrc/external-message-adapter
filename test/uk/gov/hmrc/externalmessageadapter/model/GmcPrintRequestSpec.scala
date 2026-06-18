/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.common.message.model.EmailAlert
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_CODE, TEST_EMAIL_ADDRESS_VALUE, TEST_ID, TEST_REASON, TEST_SOURCE_DATA }
import uk.gov.hmrc.externalmessageadapter.utils.SystemTimeSource

class GmcPrintRequestSpec extends PlaySpec {

  "GMC Print Request" must {
    val emailAddress = "a@a.com"

    "be created from a message" in {
      val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
      val message = MessageFixtures.gmcMessage(form = "SA300", alerts = Some(alert))
      val actual = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

      actual mustBe Some(GmcPrintRequest("EMAIL_BOUNCE", "someHashedSourceData", "a@a.com", Some("SA300")))
    }

    "be created from a message removing spaces from formId" in {
      val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
      val message = MessageFixtures.gmcMessage(form = "SA300 2024", alerts = Some(alert))
      val actual = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

      actual mustBe Some(GmcPrintRequest("EMAIL_BOUNCE", "someHashedSourceData", "a@a.com", Some("SA3002024")))
    }

    "have the correct json format" in {
      val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
      val message = MessageFixtures.gmcMessage(alerts = Some(alert), sourceData = "death star plans")
      val actual = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

      Json
        .toJson(actual)
        .toString() mustBe """{"reason":"EMAIL_BOUNCE","sourceData":"death star plans","emailAddress":"a@a.com","formId":"SA300"}"""
    }
  }

  "GmcPrintRequest.format" must {
    import GmcPrintRequest.format

    "read the json correctly" in new Setup {
      Json.parse(gmcPrintRequestJsonString).as[GmcPrintRequest] mustBe gmcPrintRequest
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(gmcPrintRequestInvalidJsonString).as[GmcPrintRequest]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(gmcPrintRequest) mustBe Json.parse(gmcPrintRequestJsonString)
    }
  }

  "GmcPrintResponseBody.format" must {
    import GmcPrintResponseBody.format

    "read the json correctly" in new Setup {
      Json.parse(gmcPrintResponseBodyJsonString).as[GmcPrintResponseBody] mustBe gmcPrintResponseBody
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(gmcPrintResponseBodyInvalidJsonString).as[GmcPrintResponseBody]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(gmcPrintResponseBody) mustBe Json.parse(gmcPrintResponseBodyJsonString)
    }
  }

  trait Setup {
    val gmcPrintRequest: GmcPrintRequest =
      GmcPrintRequest(
        reason = TEST_REASON,
        sourceData = TEST_SOURCE_DATA,
        emailAddress = TEST_EMAIL_ADDRESS_VALUE,
        formId = Some(TEST_ID),
        properties = None
      )

    val gmcPrintFailureResponse: GmcPrintFailureResponse =
      GmcPrintFailureResponse(reason = TEST_REASON, code = Some(TEST_CODE))

    val gmcPrintResponseBody: GmcPrintResponseBody = GmcPrintResponseBody(List(gmcPrintFailureResponse))

    val gmcPrintRequestJsonString: String =
      """{
        |"reason":"test_reason",
        |"sourceData":"test_data",
        |"emailAddress":"test@test.com",
        |"formId":"test_id"
        |}""".stripMargin

    val gmcPrintRequestInvalidJsonString: String =
      """{
        |"sourceData":"test_data",
        |"emailAddress":"test@test.com",
        |"formId":"test_id"
        |}""".stripMargin

    val gmcPrintResponseBodyJsonString: String =
      """{"failures":[{"reason":"test_reason","code":"test_code"}]}""".stripMargin

    val gmcPrintResponseBodyInvalidJsonString: String =
      """{"failures":[{"code":"test_code"}]}""".stripMargin
  }
}
