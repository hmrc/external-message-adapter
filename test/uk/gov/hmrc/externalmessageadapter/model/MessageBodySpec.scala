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

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_FORM, TEST_ID, TEST_TIME_STRING, TEST_TYPE }

class MessageBodySpec extends SpecBase {

  import MessageBody.format

  "Json Reads" should {
    "read the json correctly" in new Setup {
      Json.parse(messageBodyJsonString).as[MessageBody] mustBe messageBody
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(messageBodyInvalidJsonString).as[MessageBody]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(messageBody) mustBe Json.parse(messageBodyJsonString)
    }
  }

  trait Setup {
    val messageBody: MessageBody =
      MessageBody(
        `type` = Some(TEST_TYPE),
        form = Some(TEST_FORM),
        suppressedAt = Some(TEST_TIME_STRING),
        detailsId = Some(TEST_ID)
      )

    val messageBodyJsonString: String =
      """{
        |"type":"test_type",
        |"form":"test_form",
        |"suppressedAt":"2026-02-20T12:34:56Z",
        |"detailsId":"test_id"
        |}""".stripMargin

    val messageBodyInvalidJsonString: String =
      """{
        |"type":true,
        |"form":"test_form",
        |"suppressedAt":"2026-02-20T12:34:56Z",
        |"detailsId":"test_id"
        |}""".stripMargin
  }
}
