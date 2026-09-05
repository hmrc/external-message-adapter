/*
 * Copyright 2026 HM Revenue & Customs
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

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.common.message.model.EmailAlert
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_CODE, TEST_EMAIL_ADDRESS_VALUE, TEST_ID, TEST_REASON, TEST_SOURCE_DATA }
import uk.gov.hmrc.externalmessageadapter.utils.SystemTimeSource

class GmcPrintRequestSpec extends PlaySpec {

  "GmcPrintRequest" must {
    val emailAddress = "a@a.com"

    "be created from a message" in {
      val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
      val message = MessageFixtures.gmcMessage(alerts = Some(alert))
      val actual = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

      actual mustBe Some(
        GmcPrintRequest("EMAIL_BOUNCE", "someHashedSourceData", "a@a.com", Some("SA300"), None, Some("2342342341"))
      )
    }

    "be created from a message removing spaces from formId" in {
      val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
      val message = MessageFixtures.gmcMessage(form = "SA300 2024", alerts = Some(alert))
      val actual = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

      actual mustBe Some(
        GmcPrintRequest("EMAIL_BOUNCE", "someHashedSourceData", "a@a.com", Some("SA3002024"), None, Some("2342342341"))
      )
    }

    "be created from a message" when {
      "message has externalRef id" in {
        val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
        val message = MessageFixtures.gmcMessage(alerts = Some(alert))
        val actual: Option[GmcPrintRequest] = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

        actual mustBe Some(
          GmcPrintRequest(
            reason = "EMAIL_BOUNCE",
            sourceData = "someHashedSourceData",
            emailAddress = "a@a.com",
            formId = Some("SA300"),
            externalRefId = Some("2342342341")
          )
        )
      }
    }

    "have the correct json format" in {
      val alert = EmailAlert(Some(emailAddress), SystemTimeSource.now(), success = true, None)
      val message = MessageFixtures.gmcMessage(alerts = Some(alert), sourceData = "death star plans")
      val actual = GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress)

      Json
        .toJson(actual)
        .toString() mustBe """{"reason":"EMAIL_BOUNCE","sourceData":"death star plans","emailAddress":"a@a.com","formId":"SA300","externalRefId":"2342342341"}"""
    }
  }

  "GmcPrintRequest.format" must {
    import GmcPrintRequest.format

    "read the json correctly" in new Setup {
      Json.parse(gmcPrintRequestJsonString).as[GmcPrintRequest] mustBe gmcPrintRequest
      Json.parse(gmcPrintRequestWithExternalRefIdJsonString).as[GmcPrintRequest] mustBe gmcPrintRequestWithExternalRefId
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(gmcPrintRequestInvalidJsonString).as[GmcPrintRequest]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(gmcPrintRequest) mustBe Json.parse(gmcPrintRequestJsonString)
      Json.toJson(gmcPrintRequestWithExternalRefId) mustBe Json.parse(gmcPrintRequestWithExternalRefIdJsonString)
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

    val gmcPrintRequestWithExternalRefId: GmcPrintRequest =
      GmcPrintRequest(
        reason = TEST_REASON,
        sourceData = TEST_SOURCE_DATA,
        emailAddress = TEST_EMAIL_ADDRESS_VALUE,
        formId = Some(TEST_ID),
        properties = None,
        externalRefId = Some("2342342341")
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

    val gmcPrintRequestWithExternalRefIdJsonString: String =
      """{
        |"reason":"test_reason",
        |"sourceData":"test_data",
        |"emailAddress":"test@test.com",
        |"formId":"test_id",
        |"externalRefId":"2342342341"
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
