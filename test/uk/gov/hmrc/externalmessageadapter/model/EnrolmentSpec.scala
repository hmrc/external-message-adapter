/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_KEY, TEST_KEY_VALUE, TEST_NAME, TEST_SERVICE_NAME, TEST_TIME_STRING }

class EnrolmentSpec extends SpecBase {

  "Enrolment.enrolmentFormat" should {
    import Enrolment.enrolmentFormat

    "read the json correctly" in new Setup {
      Json.parse(enrolmentJsonString).as[Enrolment] mustBe enrolment
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(enrolmentInvalidJsonString).as[Enrolment]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(enrolment) mustBe Json.parse(enrolmentJsonString)
    }
  }

  "EnrolmentIdentifier.format" should {
    import EnrolmentIdentifier.format

    "read the json correctly" in new Setup {
      Json.parse(enrolmentIdentifierJsonString).as[EnrolmentIdentifier] mustBe enrolmentIdentifier
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(enrolmentIdentifierInvalidJsonString).as[EnrolmentIdentifier]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(enrolmentIdentifier) mustBe Json.parse(enrolmentIdentifierJsonString)
    }
  }

  trait Setup {
    val enrolmentIdentifier: EnrolmentIdentifier = EnrolmentIdentifier(key = TEST_KEY, value = TEST_KEY_VALUE)

    val enrolment: Enrolment = Enrolment(
      service = TEST_SERVICE_NAME,
      identifiers = Seq(enrolmentIdentifier),
      state = None,
      friendlyName = Some(TEST_NAME),
      enrolmentTokenExpiryDate = Some(TEST_TIME_STRING)
    )

    val enrolmentIdentifierJsonString: String = """{"key":"test_key","value":"test_key_value"}""".stripMargin
    val enrolmentIdentifierInvalidJsonString: String = """{"value":"test_key_value"}""".stripMargin

    val enrolmentJsonString: String =
      """{
        |"service":"test_service",
        |"identifiers":[{"key":"test_key","value":"test_key_value"}],
        |"friendlyName":"test_name",
        |"enrolmentTokenExpiryDate":"2026-02-20T12:34:56Z"
        |}""".stripMargin

    val enrolmentInvalidJsonString: String =
      """{
        |"identifiers":[{"key":"test_key","value":"test_key_value"}],
        |"friendlyName":"test_name",
        |"enrolmentTokenExpiryDate":"2026-02-20T12:34:56Z"
        |}""".stripMargin
  }
}
