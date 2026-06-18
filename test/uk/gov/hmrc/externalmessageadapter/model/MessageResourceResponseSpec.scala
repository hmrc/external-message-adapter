/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_SERVICE_NAME, TEST_URL }

class MessageResourceResponseSpec extends SpecBase {

  "ServiceUrl" should {
    ServiceUrl.fmt

    "read the json correctly" in new Setup {
      Json.parse(serviceUrlJsonString).as[ServiceUrl] mustBe serviceUrl
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(serviceUrlInvalidJsonString).as[ServiceUrl]
      }
    }

    "read the object correctly" in new Setup {
      Json.toJson(serviceUrl) mustBe Json.parse(serviceUrlJsonString)
    }
  }

  trait Setup {
    val serviceUrl: ServiceUrl = ServiceUrl(service = TEST_SERVICE_NAME, url = TEST_URL)

    val serviceUrlJsonString: String =
      """{"service":"test_service","url":"http://localhost:9000/test_url"}""".stripMargin

    val serviceUrlInvalidJsonString: String =
      """{"url":"http://localhost:9000/test_url"}""".stripMargin
  }
}
