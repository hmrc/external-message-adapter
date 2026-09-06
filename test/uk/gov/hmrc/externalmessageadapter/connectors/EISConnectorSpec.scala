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

package uk.gov.hmrc.externalmessageadapter.connectors

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import com.fasterxml.jackson.databind.{ JsonNode, ObjectMapper }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status
import play.api.inject.bind
import play.api.{ Application, Configuration }
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.externalmessageadapter.model.{ GmcPrintRequest, GmcPrintResponse }
import uk.gov.hmrc.externalmessageadapter.util.{ SpecBase, WireMockSupportProvider, WithWireMock }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ Authorization, HeaderCarrier, HttpReads, HttpResponse }
import com.typesafe.config.ConfigFactory
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import com.github.tomakehurst.wiremock.client.WireMock.{ badRequest, created, equalTo, jsonResponse, matchingJsonPath, ok, post, serviceUnavailable, trace, urlPathMatching }
import play.api.http.Status.{ BAD_REQUEST, INTERNAL_SERVER_ERROR, OK }
import play.api.libs.json.Json

import java.net.URL
import scala.concurrent.{ ExecutionContext, Future }

class EISConnectorSpec extends SpecBase with GuiceOneAppPerSuite with WireMockSupportProvider with IntegrationPatience {

  "EIS connector post" must {
    import GmcPrintRequest.format

    "allow us to request a paper version of a message" in new TestCaseWithHipDisabled {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      wireMockServer.stubFor(
        post(urlPathMatching(eisEndPoint))
          .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
          .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
          .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
          .willReturn(ok(Json.toJson(expectedBody).toString))
      )

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe None
    }

    "handle Bad request" in new TestCaseWithHipDisabled {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse =
        """{"failures":[{"code":"INVALID_PAYLOAD","reason":"Submission has not passed validation. Invalid payload."}]}"""

      val objectMapper = new ObjectMapper()
      val jsonNode: JsonNode = objectMapper.readTree(expectedResponse)

      wireMockServer.stubFor(
        post(urlPathMatching(eisEndPoint))
          .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
          .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
          .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
          .willReturn(jsonResponse(expectedResponse, BAD_REQUEST))
      )

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(
        GmcPrintResponse(BAD_REQUEST, "Submission has not passed validation. Invalid payload.")
      )
    }

    "handle Bad request when downstream call return an unexpected body" in new TestCaseWithHipDisabled {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse = """{"unknown": "response"}"""

      wireMockServer.stubFor(
        post(urlPathMatching(eisEndPoint))
          .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
          .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
          .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
          .willReturn(jsonResponse(expectedResponse, BAD_REQUEST))
      )

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(GmcPrintResponse(BAD_REQUEST, "Unknown eis error"))
    }

    "handle Internal Server" in new TestCaseWithHipDisabled {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse =
        """{"failures":[{"code":"SERVER_ERROR","reason":"IF is currently experiencing problems that require live service intervention."}]}"""

      wireMockServer.stubFor(
        post(urlPathMatching(eisEndPoint))
          .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
          .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
          .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
          .willReturn(jsonResponse(expectedResponse, INTERNAL_SERVER_ERROR))
      )

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(
        GmcPrintResponse(
          INTERNAL_SERVER_ERROR,
          "IF is currently experiencing problems that require live service intervention."
        )
      )
    }

    "handle Internal Server when downstream call return an unexpected response body" in new TestCaseWithHipDisabled {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse = """{"unknown": "response"}"""

      wireMockServer.stubFor(
        post(urlPathMatching(eisEndPoint))
          .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
          .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
          .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
          .willReturn(jsonResponse(expectedResponse, INTERNAL_SERVER_ERROR))
      )

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(GmcPrintResponse(Status.INTERNAL_SERVER_ERROR, "Unknown eis error"))
    }

    "send request to correct endpoint" when {

      "hip.email-bounce-back is disabled" in new TestCaseWithHipDisabled {
        val expectedResponse = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

        wireMockServer.stubFor(
          post(urlPathMatching(eisEndPoint))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
            .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
            .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
            .willReturn(jsonResponse(expectedResponse, OK))
        )

        val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

        val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

        result.futureValue mustBe None
      }

      "hip.email-bounce-back is disabled and formId is" +
        " one of that are part of bounceback.formIds (API 5951)" in new TestCaseWithHipDisabled {
          val expectedResponse =
            """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

          wireMockServer.stubFor(
            post(urlPathMatching(eisEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1708")))
              .willReturn(jsonResponse(expectedResponse, OK))
          )

          val reprintRequest: GmcPrintRequest =
            GmcPrintRequest(
              reason = "EMAIL_BOUNCE",
              sourceData = "Some Hashed Data",
              emailAddress = "a@a.com",
              formId = Some("CH(A)1708")
            )

          val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

          result.futureValue mustBe None
        }

      "hip.email-bounce-back is enabled and formId is" +
        " one of that are part of bounceback.formIds (API 5951)" in new TestCaseWithHipEnabled {
          val expectedResponse =
            """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

          wireMockServer.stubFor(
            post(urlPathMatching(hipEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
              .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("U0582898ZZ2G4F88AAG")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Bearer AbCdEf123456"))
              .willReturn(jsonResponse(expectedResponse, OK))
          )

          val reprintRequest: GmcPrintRequest =
            GmcPrintRequest(
              "EMAIL_BOUNCE",
              "Some Hashed Data",
              "a@a.com",
              Some("CH(A)1700"),
              None,
              Some("U0582898ZZ2G4F88AAG")
            )

          val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

          result.futureValue mustBe None
        }

      "hip.email-bounce-back is enabled and formId is not" +
        " one of that are part of bounceback.formIds (API 5951)" in new TestCaseWithHipEnabled {
          val expectedResponse =
            """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

          wireMockServer.stubFor(
            post(urlPathMatching(eisEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(matchingJsonPath("$.sourceData", equalTo("Some Hashed Data")))
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo("a@a.com")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("SA400")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Bearer AbCdEf123456"))
              .willReturn(jsonResponse(expectedResponse, OK))
          )

          val reprintRequest: GmcPrintRequest =
            GmcPrintRequest(
              "EMAIL_BOUNCE",
              "Some Hashed Data",
              "a@a.com",
              Some("SA400")
            )

          val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

          result.futureValue mustBe None
        }
    }
  }

  override def config: Configuration = Configuration(
    ConfigFactory.parseString(
      s"""
         |microservice {
         |  services {
         |  eis {
         |            host = $wireMockHost
         |            port = $wireMockPort
         |        }
         |  hip {
         |            host = $wireMockHost
         |            port = $wireMockPort
         |        }
         |  }
         |}
         |""".stripMargin
    )
  )

  trait TestCaseWithHipDisabled {

    val eisEndPoint = "/sa-forms/suppression/send-letter"
    val authToken = "authToken23432"

    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))
    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

    val mockServiceConfig: ServicesConfig = mock[ServicesConfig]
    val application: Application = new GuiceApplicationBuilder()
      .configure(
        "play.filters.csp.nonce.enabled"        -> false,
        "auditing.enabled"                      -> "false",
        "microservice.metrics.graphite.enabled" -> "false",
        "metrics.enabled"                       -> "false"
      )
      .configure(config)
      .build()

    val eisConnector: EISConnector = application.injector.instanceOf[EISConnector]
  }

  trait TestCaseWithHipEnabled {

    val hipEndPoint = "/emailBounceback"
    val eisEndPoint = "/sa-forms/suppression/send-letter"
    val authToken = "authToken23432"

    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))
    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

    val mockServiceConfig: ServicesConfig = mock[ServicesConfig]
    val application: Application = new GuiceApplicationBuilder()
      .configure(
        "play.filters.csp.nonce.enabled"                      -> false,
        "auditing.enabled"                                    -> "false",
        "microservice.metrics.graphite.enabled"               -> "false",
        "metrics.enabled"                                     -> "false",
        "microservice.services.hip.email-bounce-back.enabled" -> true
      )
      .configure(config)
      .build()

    val eisConnector: EISConnector = application.injector.instanceOf[EISConnector]
  }

}
