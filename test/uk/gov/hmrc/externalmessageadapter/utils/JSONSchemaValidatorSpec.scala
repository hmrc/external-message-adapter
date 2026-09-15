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

package uk.gov.hmrc.externalmessageadapter.utils

import org.scalatest.TryValues
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running
import uk.gov.hmrc.externalmessageadapter.util.SpecBase

import scala.util.Try

class JSONSchemaValidatorSpec extends SpecBase with TryValues with GuiceOneAppPerSuite with JsonFileReader {
  val emailBounceBackSchemaPath = "/schemas/email_bounce_back_API_5951_schema_v1.0.json"
  val emailBounceBackValidRequest = "/email_bounce_back_request_valid.json"
  val emailBounceBackInvalidRequest = "/email_bounce_back_request_invalid.json"

  "ssfnRequestSchema" should {
    "return correct value for the schema path" in new Setup {
      jsonPayloadSchemaValidator.emailBounceBackSchema mustBe emailBounceBackSchemaPath
    }
  }

  "validateJson" must {
    "validate the emailBounceBack valid request" in new Setup {
      val result: Try[Unit] = jsonPayloadSchemaValidator.validatePayload(
        readJsonFromFile(emailBounceBackValidRequest),
        emailBounceBackSchemaPath
      )
      result.success.value mustBe ()
    }

    "return error for invalid emailBounceBack request" in new Setup {
      val result: Try[Unit] = jsonPayloadSchemaValidator.validatePayload(
        readJsonFromFile(emailBounceBackInvalidRequest),
        emailBounceBackSchemaPath
      )

      result.isFailure mustBe true
      result.failure.exception.getMessage must include(
        "(/formId: string \"this_form_id_invalid_as_it_is_longer_than_thirty_characters\"" +
          " is too long (length: 59, maximum allowed: 30)):::(/formId: ECMA 262 regex \"^.{0,30}$"
      )
    }
  }

  trait Setup {
    val app: Application = new GuiceApplicationBuilder()
      .configure(
        "play.filters.csp.nonce.enabled"        -> false,
        "auditing.enabled"                      -> "false",
        "microservice.metrics.graphite.enabled" -> "false",
        "metrics.enabled"                       -> "false"
      )
      .build()
    val jsonPayloadSchemaValidator: JSONSchemaValidator = app.injector.instanceOf[JSONSchemaValidator]
  }
}
