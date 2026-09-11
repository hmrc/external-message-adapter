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

package uk.gov.hmrc.externalmessageadapter

import com.typesafe.config.ConfigFactory
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.{ FakeHeaders, FakeRequest }
import play.api.test.Helpers.{ NO_CONTENT, POST, route, status }
import play.api.{ Application, Configuration, inject }
import uk.gov.hmrc.externalmessageadapter.model.{ EventBody, EventHubEvent }
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_EMAIL_ADDRESS_VALUE, TEST_ID, TEST_LOCAL_DATE, TEST_LOCAL_DATE_TIME, TEST_MESSAGE, TEST_REASON }
import uk.gov.hmrc.externalmessageadapter.util.{ SpecBase, WireMockSupportProvider }
import uk.gov.hmrc.externalmessageadapter.validators.MessagesUtil
import uk.gov.hmrc.http.{ Authorization, HeaderCarrier }
import org.mockito.Mockito.when
import org.mockito.ArgumentMatchers.any
import play.api.libs.json.Json
import play.api.mvc.Result
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Success
import play.api.test.*
import play.api.test.Helpers.*
import uk.gov.hmrc.common.message.model.{ Details, Message }
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod.{ POST => WIREMOCK_POST }

import scala.concurrent.{ ExecutionContext, Future }

class EventHubProcessorControllerISpec extends SpecBase with GuiceOneAppPerSuite with WireMockSupportProvider {

  "processEventHubEvents (/message-process-eventhub-events)" must {

    "process the event successfully" when {

      "event is BounceEvent and Message formId is CH(A)1700 and paper notification" +
        " is to be send over HIP" in new TestCaseWithHipEnabled {
          import EventHubEvent.formats

          val request = FakeRequest(
            POST,
            "/message-process-eventhub-events",
            FakeHeaders(),
            Json.toJson(eventHubEvent)
          )

          when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
          when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

          wireMockServer.stubFor(
            post(urlPathMatching(hipEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
              .willReturn(ok.withHeader("correlationid", "e470d65899f74292a4a1ed12c72f1337"))
          )

          when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

          val result: Future[Result] = route(application, request).head
          status(result) mustBe NO_CONTENT

          verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
        }

      "event is BounceEvent and paper notification is to be send over EIS " in new TestCaseWithHipDisabled {

        import EventHubEvent.formats

        val request = FakeRequest(
          POST,
          "/message-process-eventhub-events",
          FakeHeaders(),
          Json.toJson(eventHubEvent)
        )

        when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
        when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(TEST_MESSAGE)))

        wireMockServer.stubFor(
          post(urlPathMatching(eisEndPoint))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
            .withRequestBody(
              matchingJsonPath(
                "$.sourceData",
                equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
              )
            )
            .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
            .withRequestBody(matchingJsonPath("$.formId", equalTo("SA300")))
            .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(AUTHORIZATION, equalTo("Bearer AbCdEf123456"))
            .willReturn(ok.withHeader("correlationid", "e470d65899f74292a4a1ed12c72f1337"))
        )

        when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

        val result: Future[Result] = route(application, request).head
        status(result) mustBe NO_CONTENT

        verifyExactlyOneEndPointUrlHit(eisEndPoint, WIREMOCK_POST)
      }
    }

    "return the correct error code" when {
      "event is BounceEvent and Message formId is CH(A)1700 and paper notification" +
        " is to be send over HIP but upstream response is of INTERNAL_SERVER_ERROR" in new TestCaseWithHipEnabled {

          import EventHubEvent.formats

          val request = FakeRequest(
            POST,
            "/message-process-eventhub-events",
            FakeHeaders(),
            Json.toJson(eventHubEvent)
          )

          val expectedResponse: String =
            """{
              |  "origin": "HIP",
              |  "response": {
              |    "failures": [
              |      {
              |        "type": "Service Unavailable",
              |        "reason": "service is unavailable due to network layer is down"
              |      }
              |    ]
              |  }
              |}""".stripMargin

          when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
          when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

          wireMockServer.stubFor(
            post(urlPathMatching(hipEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
              .willReturn(jsonResponse(expectedResponse, INTERNAL_SERVER_ERROR))
          )

          when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

          val result: Future[Result] = route(application, request).head
          status(result) mustBe INTERNAL_SERVER_ERROR

          verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
        }

      " is to be send over HIP but upstream response is of BAD_REQUEST" in new TestCaseWithHipEnabled {

        import EventHubEvent.formats

        val request = FakeRequest(
          POST,
          "/message-process-eventhub-events",
          FakeHeaders(),
          Json.toJson(eventHubEvent)
        )

        val expectedResponse: String =
          """{
            |  "origin": "HIP",
            |  "response": {
            |    "failures": [
            |      {
            |        "type": "body.schema.pattern",
            |        "reason": "Path '/emailAddress' validation failed."
            |      }
            |    ]
            |  }
            |}""".stripMargin

        when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
        when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

        wireMockServer.stubFor(
          post(urlPathMatching(hipEndPoint))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
            .withRequestBody(
              matchingJsonPath(
                "$.sourceData",
                equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
              )
            )
            .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
            .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
            .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
            .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
            .willReturn(jsonResponse(expectedResponse, BAD_REQUEST))
        )

        when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

        val result: Future[Result] = route(application, request).head
        status(result) mustBe BAD_REQUEST

        verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
      }

      " is to be send over HIP but upstream response is of UNAUTHORIZED" in new TestCaseWithHipEnabled {

        import EventHubEvent.formats

        val request = FakeRequest(
          POST,
          "/message-process-eventhub-events",
          FakeHeaders(),
          Json.toJson(eventHubEvent)
        )

        val expectedResponse: String = """{"message":"Authentication information is missing or invalid"}""".stripMargin

        when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
        when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

        wireMockServer.stubFor(
          post(urlPathMatching(hipEndPoint))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
            .withRequestBody(
              matchingJsonPath(
                "$.sourceData",
                equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
              )
            )
            .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
            .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
            .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
            .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
            .willReturn(jsonResponse(expectedResponse, UNAUTHORIZED))
        )

        when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

        val result: Future[Result] = route(application, request).head
        status(result) mustBe UNAUTHORIZED

        verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
      }

      " is to be send over HIP but upstream response is of FORBIDDEN" in new TestCaseWithHipEnabled {

        import EventHubEvent.formats

        val request = FakeRequest(
          POST,
          "/message-process-eventhub-events",
          FakeHeaders(),
          Json.toJson(eventHubEvent)
        )

        val expectedResponse: String = """{"message":"Forbidden"}""".stripMargin

        when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
        when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

        wireMockServer.stubFor(
          post(urlPathMatching(hipEndPoint))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
            .withRequestBody(
              matchingJsonPath(
                "$.sourceData",
                equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
              )
            )
            .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
            .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
            .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
            .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
            .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
            .willReturn(jsonResponse(expectedResponse, FORBIDDEN))
        )

        when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

        val result: Future[Result] = route(application, request).head
        status(result) mustBe FORBIDDEN

        verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
      }

      "event is BounceEvent and paper notification is to be send over EIS" +
        " but upstream response is of INTERNAL_SERVER_ERROR" in new TestCaseWithHipDisabled {

          import EventHubEvent.formats

          val request = FakeRequest(
            POST,
            "/message-process-eventhub-events",
            FakeHeaders(),
            Json.toJson(eventHubEvent)
          )

          val expectedResponse: String =
            """{
              |    "failures": [
              |      {
              |        "type": "INTERNAL_SERVER_ERROR",
              |        "reason": "server error occurred"
              |      }
              |    ]
              |}""".stripMargin

          when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
          when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(TEST_MESSAGE)))

          wireMockServer.stubFor(
            post(urlPathMatching(eisEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("SA300")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Bearer AbCdEf123456"))
              .willReturn(jsonResponse(expectedResponse, INTERNAL_SERVER_ERROR))
          )

          when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

          val result: Future[Result] = route(application, request).head
          status(result) mustBe INTERNAL_SERVER_ERROR

          verifyExactlyOneEndPointUrlHit(eisEndPoint, WIREMOCK_POST)
        }
    }

    "return the correct response" when {

      "request is retried over EIS due to INTERNAL_SERVER_ERROR response" +
        " received over HIP" in new TestCaseWithHipAndFallBackToEISEnabled {

          import EventHubEvent.formats

          val request = FakeRequest(
            POST,
            "/message-process-eventhub-events",
            FakeHeaders(),
            Json.toJson(eventHubEvent)
          )

          val expectedHIPResponse: String =
            """{
              |  "origin": "HIP",
              |  "response": {
              |    "failures": [
              |      {
              |        "type": "Service Unavailable",
              |        "reason": "service is unavailable due to network layer is down"
              |      }
              |    ]
              |  }
              |}""".stripMargin

          val expectedEISResponse =
            """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

          when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
          when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

          wireMockServer.stubFor(
            post(urlPathMatching(hipEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
              .willReturn(jsonResponse(expectedHIPResponse, INTERNAL_SERVER_ERROR))
          )

          wireMockServer.stubFor(
            post(urlPathMatching(eisEndPoint))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .willReturn(jsonResponse(expectedEISResponse, OK))
          )

          when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

          val result: Future[Result] = route(application, request).head
          status(result) mustBe NO_CONTENT

          verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
          verifyExactlyOneEndPointUrlHit(eisEndPoint, WIREMOCK_POST)
        }

      "request is retried over EIS due to BAD_REQUEST response received" +
        " over HIP" in new TestCaseWithHipAndFallBackToEISEnabled {

          import EventHubEvent.formats

          val request = FakeRequest(
            POST,
            "/message-process-eventhub-events",
            FakeHeaders(),
            Json.toJson(eventHubEvent)
          )

          val expectedHIPResponse: String =
            """{
              |  "origin": "HIP",
              |  "response": {
              |    "failures": [
              |      {
              |        "type": "header.correlationid",
              |        "reason": "The request parameter header.correlationid failed validation due to pattern mismatch."
              |      },
              |      {
              |        "type": "body.schema.pattern",
              |        "reason": "Path '/emailAddress' validation failed."
              |      }
              |    ]
              |  }
              |}""".stripMargin

          val expectedEISResponse =
            """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

          when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
          when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

          wireMockServer.stubFor(
            post(urlPathMatching(hipEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
              .willReturn(jsonResponse(expectedHIPResponse, BAD_REQUEST))
          )

          wireMockServer.stubFor(
            post(urlPathMatching(eisEndPoint))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .willReturn(jsonResponse(expectedEISResponse, OK))
          )

          when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

          val result: Future[Result] = route(application, request).head
          status(result) mustBe NO_CONTENT

          verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
          verifyExactlyOneEndPointUrlHit(eisEndPoint, WIREMOCK_POST)
        }

      "request is retried over EIS due to UNAUTHORIZED response received" +
        " over HIP" in new TestCaseWithHipAndFallBackToEISEnabled {

          import EventHubEvent.formats

          val request = FakeRequest(
            POST,
            "/message-process-eventhub-events",
            FakeHeaders(),
            Json.toJson(eventHubEvent)
          )

          val expectedHIPResponse: String =
            """{"message":"Authentication information is missing or invalid"}""".stripMargin

          val expectedEISResponse =
            """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

          when(mockMessagesUtil.auditMessageDeliveryStatus(any)(any)).thenReturn(Future.successful(Success))
          when(mockMsgRepository.findByExternalRefId(any[String])).thenReturn(Future.successful(Option(MSG)))

          wireMockServer.stubFor(
            post(urlPathMatching(hipEndPoint))
              .withRequestBody(matchingJsonPath("$.reason", equalTo("EMAIL_BOUNCE")))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.externalRefId", equalTo("2342342341")))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .withHeader(CONTENT_TYPE, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(ACCEPT, equalTo(CONTENT_TYPE_APPLICATION_JSON))
              .withHeader(AUTHORIZATION, equalTo("Basic QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="))
              .willReturn(jsonResponse(expectedHIPResponse, UNAUTHORIZED))
          )

          wireMockServer.stubFor(
            post(urlPathMatching(eisEndPoint))
              .withRequestBody(
                matchingJsonPath(
                  "$.sourceData",
                  equalTo("ew0KICAgIm5hbWUiOiAiRGFuaWVsIiwNCiAgICJzZWF0IiA6ICJ5ZXMiDQp9")
                )
              )
              .withRequestBody(matchingJsonPath("$.emailAddress", equalTo(TEST_EMAIL_ADDRESS_VALUE)))
              .withRequestBody(matchingJsonPath("$.formId", equalTo("CH(A)1700")))
              .willReturn(jsonResponse(expectedEISResponse, OK))
          )

          when(mockMsgRepository.removeById(any)).thenReturn(Future.successful(true))

          val result: Future[Result] = route(application, request).head
          status(result) mustBe NO_CONTENT

          verifyExactlyOneEndPointUrlHit(hipEndPoint, WIREMOCK_POST)
          verifyExactlyOneEndPointUrlHit(eisEndPoint, WIREMOCK_POST)
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

    val eventBody: EventBody =
      EventBody(
        event = "permanentbounce",
        emailAddress = TEST_EMAIL_ADDRESS_VALUE,
        detected = TEST_LOCAL_DATE_TIME,
        code = 2,
        reason = TEST_REASON,
        tags = Map("messageId" -> "6a5645a2c0510b9d8d982ebd")
      )

    val eventHubEvent: EventHubEvent =
      EventHubEvent(eventId = TEST_ID, timestamp = TEST_LOCAL_DATE_TIME, event = eventBody)

    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))
    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

    val mockMessagesUtil: MessagesUtil = mock[MessagesUtil]
    val mockMsgRepository: MongoMessageRepository = mock[MongoMessageRepository]

    val application: Application = new GuiceApplicationBuilder()
      .overrides(
        inject.bind[MessagesUtil].toInstance(mockMessagesUtil),
        inject.bind[MongoMessageRepository].toInstance(mockMsgRepository)
      )
      .configure(
        "play.filters.csp.nonce.enabled"        -> false,
        "auditing.enabled"                      -> "false",
        "microservice.metrics.graphite.enabled" -> "false",
        "metrics.enabled"                       -> "false",
        "handle.bounce.eventhub"                -> true
      )
      .configure(config)
      .build()
  }

  trait TestCaseWithHipEnabled {

    val hipEndPoint = "/emailBounceback"
    val eisEndPoint = "/sa-forms/suppression/send-letter"
    val authToken = "authToken23432"

    val eventBody: EventBody =
      EventBody(
        event = "permanentbounce",
        emailAddress = TEST_EMAIL_ADDRESS_VALUE,
        detected = TEST_LOCAL_DATE_TIME,
        code = 2,
        reason = TEST_REASON,
        tags = Map("messageId" -> "6a5645a2c0510b9d8d982ebd")
      )

    val eventHubEvent: EventHubEvent =
      EventHubEvent(eventId = TEST_ID, timestamp = TEST_LOCAL_DATE_TIME, event = eventBody)

    val details = Details(
      Some("CH(A)1700"),
      Some("print-suppression-notification"),
      Some(TEST_LOCAL_DATE.minusDays(1).toString),
      Some("C0123456781234568")
    )

    val MSG: Message = TEST_MESSAGE.copy(body = Some(details))

    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))
    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

    val mockMessagesUtil: MessagesUtil = mock[MessagesUtil]
    val mockMsgRepository: MongoMessageRepository = mock[MongoMessageRepository]

    val application: Application = new GuiceApplicationBuilder()
      .overrides(
        inject.bind[MessagesUtil].toInstance(mockMessagesUtil),
        inject.bind[MongoMessageRepository].toInstance(mockMsgRepository)
      )
      .configure(
        "play.filters.csp.nonce.enabled"                      -> false,
        "auditing.enabled"                                    -> "false",
        "microservice.metrics.graphite.enabled"               -> "false",
        "metrics.enabled"                                     -> "false",
        "microservice.services.hip.email-bounce-back.enabled" -> true,
        "handle.bounce.eventhub"                              -> true
      )
      .configure(config)
      .build()
  }

  trait TestCaseWithHipAndFallBackToEISEnabled {

    val hipEndPoint = "/emailBounceback"
    val eisEndPoint = "/sa-forms/suppression/send-letter"
    val authToken = "authToken23432"

    val eventBody: EventBody =
      EventBody(
        event = "permanentbounce",
        emailAddress = TEST_EMAIL_ADDRESS_VALUE,
        detected = TEST_LOCAL_DATE_TIME,
        code = 2,
        reason = TEST_REASON,
        tags = Map("messageId" -> "6a5645a2c0510b9d8d982ebd")
      )

    val eventHubEvent: EventHubEvent =
      EventHubEvent(eventId = TEST_ID, timestamp = TEST_LOCAL_DATE_TIME, event = eventBody)

    val details = Details(
      Some("CH(A)1700"),
      Some("print-suppression-notification"),
      Some(TEST_LOCAL_DATE.minusDays(1).toString),
      Some("C0123456781234568")
    )

    val MSG: Message = TEST_MESSAGE.copy(body = Some(details))

    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))
    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

    val mockMessagesUtil: MessagesUtil = mock[MessagesUtil]
    val mockMsgRepository: MongoMessageRepository = mock[MongoMessageRepository]

    val application: Application = new GuiceApplicationBuilder()
      .overrides(
        inject.bind[MessagesUtil].toInstance(mockMessagesUtil),
        inject.bind[MongoMessageRepository].toInstance(mockMsgRepository)
      )
      .configure(
        "play.filters.csp.nonce.enabled"                                       -> false,
        "auditing.enabled"                                                     -> "false",
        "microservice.metrics.graphite.enabled"                                -> "false",
        "metrics.enabled"                                                      -> "false",
        "microservice.services.hip.email-bounce-back.enabled"                  -> true,
        "handle.bounce.eventhub"                                               -> true,
        "microservice.services.hip.email-bounce-back.fall-back-to-eis-enabled" -> true
      )
      .configure(config)
      .build()
  }
}
