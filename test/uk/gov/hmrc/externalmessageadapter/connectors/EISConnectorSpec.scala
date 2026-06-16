/*
 * Copyright 2023 HM Revenue & Customs
 *
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
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.externalmessageadapter.model.{ GmcPrintRequest, GmcPrintResponse }
import uk.gov.hmrc.externalmessageadapter.util.WithWireMock
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ Authorization, HeaderCarrier, HttpReads, HttpResponse }

import java.net.URL
import scala.concurrent.{ ExecutionContext, Future }

class EISConnectorSpec
    extends PlaySpec with GuiceOneAppPerSuite with ScalaFutures with WithWireMock with IntegrationPatience {

  lazy val mockHttp: HttpClientV2 = MockitoSugar.mock[HttpClientV2]
  lazy val requestBuilder: RequestBuilder = MockitoSugar.mock[RequestBuilder]

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .overrides(bind[HttpClientV2].toInstance(mockHttp))
      .configure("metrics.enabled" -> "false")
      .build()

  lazy val eisConnector: EISConnector = app.injector.instanceOf[EISConnector]
  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  override def dependenciesPort: Int =
    app.configuration
      .getOptional[Int]("microservice.services.eis.port")
      .getOrElse(throw new Exception("Port missing for Eis"))

  "EIS connector post" must {
    "allow us to request a paper version of a message" in new TestCase {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(Status.OK, "")))

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe None
    }

    "handle Bad request" in new TestCase {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse =
        """{"failures":[{"code":"INVALID_PAYLOAD","reason":"Submission has not passed validation. Invalid payload."}]}"""
      val objectMapper = new ObjectMapper()
      val jsonNode: JsonNode = objectMapper.readTree(expectedResponse)

      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(Status.BAD_REQUEST, expectedResponse)))

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(
        GmcPrintResponse(Status.BAD_REQUEST, "Submission has not passed validation. Invalid payload.")
      )
    }

    "handle Bad request when downstream call return an unexpected body" in new TestCase {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse =
        """{"unknown": "response"}"""
      val objectMapper = new ObjectMapper()
      val jsonNode: JsonNode = objectMapper.readTree(expectedResponse)

      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(Status.BAD_REQUEST, expectedResponse)))

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(GmcPrintResponse(Status.BAD_REQUEST, "Unknown eis error"))
    }

    "handle Internal Server" in new TestCase {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse =
        """{"failures":[{"code":"SERVER_ERROR","reason":"IF is currently experiencing problems that require live service intervention."}]}"""
      val objectMapper = new ObjectMapper()
      val jsonNode: JsonNode = objectMapper.readTree(expectedResponse)

      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(Status.INTERNAL_SERVER_ERROR, expectedResponse)))

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(
        GmcPrintResponse(
          Status.INTERNAL_SERVER_ERROR,
          "IF is currently experiencing problems that require live service intervention."
        )
      )
    }

    "handle Internal Server when downstream call return an unexpected response body" in new TestCase {
      val expectedBody = """{"reason":"EMAIL_BOUNCE","sourceData":"Some Hashed Data","emailAddress":"a@a.com"}"""

      val expectedResponse =
        """{"unknown": "response"}"""
      val objectMapper = new ObjectMapper()
      val jsonNode: JsonNode = objectMapper.readTree(expectedResponse)

      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(Status.INTERNAL_SERVER_ERROR, expectedResponse)))

      val reprintRequest: GmcPrintRequest = GmcPrintRequest("EMAIL_BOUNCE", "Some Hashed Data", "a@a.com")

      val result: Future[Option[GmcPrintResponse]] = eisConnector.post(reprintRequest, "correlationId")

      result.futureValue mustBe Some(GmcPrintResponse(Status.INTERNAL_SERVER_ERROR, "Unknown eis error"))
    }
  }

  trait TestCase {

    val authToken = "authToken23432"

    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))

    when(mockHttp.post(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)

    when(requestBuilder.withBody(any)(any, any, any)).thenReturn(requestBuilder)

    when(requestBuilder.setHeader(any)).thenReturn(requestBuilder)

  }

}
