/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.{ any, eq as equalTo }
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.*
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import uk.gov.hmrc.common.message.model.Enrolments
import uk.gov.hmrc.externalmessageadapter.MetricOrchestratorStub
import uk.gov.hmrc.externalmessageadapter.model.Users
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator

import java.net.{ URI, URL }
import scala.concurrent.{ ExecutionContext, Future }

class EnrolmentProxyConnectorSpec extends PlaySpec with ScalaFutures with MockitoSugar with MetricOrchestratorStub {

  lazy val mockHttp: HttpClientV2 = mock[HttpClientV2]
  lazy val requestBuilder = mock[RequestBuilder]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[HttpClientV2].toInstance(mockHttp))
    .configure("metrics.enabled" -> "false")
    .injector()

  lazy val connector: EnrolmentProxyConnector = injector.instanceOf[EnrolmentProxyConnector]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  "Retrieve Enrolments" must {
    "check for list of principal userIds" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(
          Future.successful(
            HttpResponse(
              OK,
              Json.obj("principalUserIds" -> List("123456", "789012"), "delegatedUserIds" -> List.empty[String]),
              Map.empty[String, Seq[String]]
            )
          )
        )
      connector.enrolments(Enrolments("someEnrolmentKey")).futureValue mustBe Right(
        "someEnrolmentKey" -> Some(Users(List("123456", "789012")))
      )
    }

    "check for list of principal userIds for an enrolmentKey having spaces" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(
          Future.successful(
            HttpResponse(
              OK,
              Json.obj("principalUserIds" -> List("123456", "789012"), "delegatedUserIds" -> List.empty[String]),
              Map.empty[String, Seq[String]]
            )
          )
        )
      connector.enrolments(Enrolments("some Enrolment Key")).futureValue mustBe Right(
        "some Enrolment Key" -> Some(Users(List("123456", "789012")))
      )
    }

    "check for list of no principal userIds but having delegateUserids" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(
          Future.successful(
            HttpResponse(
              OK,
              Json.obj("principalUserIds" -> List.empty[String], "delegatedUserIds" -> List.empty[String]),
              Map.empty[String, Seq[String]]
            )
          )
        )
      val enrolments = connector.enrolments(Enrolments("someEnrolmentKey")).futureValue
      enrolments.isRight mustBe true
      enrolments.foreach { case (_, users) => users.get.principalUserIds mustBe List.empty[String] }
    }

    "check for no principal userIds" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(NO_CONTENT, "")))

      connector.enrolments(Enrolments("someEnrolmentKey")).futureValue mustBe Right("someEnrolmentKey" -> None)
    }

    "check for invalid enrolment key" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(NOT_FOUND, "")))

      connector.enrolments(Enrolments("someEnrolmentKey")).futureValue mustBe Left(
        "Invalid enrolment key someEnrolmentKey"
      )
    }
  }

  "hasActiveUsers" must {
    lazy val enrolmentProxyURL =
      "http://localhost:7775/enrolment-store-proxy/enrolment-store/users/%s/enrolments/someEnrolmentKey"
    val formatUrl = (userName: String) => URI(enrolmentProxyURL.format(userName)).toURL
    "check for the active user" in {
      val activeEnrolment =
        Json.obj("service" -> "someService", "identifiers" -> Seq.empty[String], "state" -> Some("Activated"))
      val activeEnrolment1 =
        Json.obj("service" -> "someService", "identifiers" -> Seq.empty[String], "state" -> Some("NotYetActivated"))

      val requestBuilder1 = mock[RequestBuilder]
      when(mockHttp.get(equalTo(formatUrl("1234")))(any[HeaderCarrier])).thenReturn(requestBuilder1)
      when(requestBuilder1.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, activeEnrolment, Map.empty[String, Seq[String]])))
      val requestBuilder2 = mock[RequestBuilder]
      when(mockHttp.get(equalTo(formatUrl("5678")))(any[HeaderCarrier])).thenReturn(requestBuilder2)
      when(requestBuilder2.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, activeEnrolment1, Map.empty[String, Seq[String]])))
      connector.hasActiveUsers(List("1234", "5678"), "someEnrolmentKey").futureValue mustBe true
    }

    "check for the in-active user" in {
      val activeEnrolment =
        Json.obj("service" -> "someService", "identifiers" -> Seq.empty[String], "state" -> Some("NotYetActivated"))
      val inActiveEnrolment =
        Json.obj("service" -> "someService", "identifiers" -> Seq.empty[String], "state" -> Some("NotYetActivated"))
      val requestBuilder1 = mock[RequestBuilder]
      when(mockHttp.get(equalTo(formatUrl("1234")))(any[HeaderCarrier])).thenReturn(requestBuilder1)
      when(requestBuilder1.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, activeEnrolment, Map.empty[String, Seq[String]])))
      val requestBuilder2 = mock[RequestBuilder]
      when(mockHttp.get(equalTo(formatUrl("5678")))(any[HeaderCarrier])).thenReturn(requestBuilder2)
      when(requestBuilder2.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, inActiveEnrolment, Map.empty[String, Seq[String]])))
      connector.hasActiveUsers(List("1234", "5678"), "someEnrolmentKey").futureValue mustBe false
    }

    "return false when downstream call return a 404" in {
      val activeEnrolment =
        Json.obj("service" -> "someService", "identifiers" -> Seq.empty[String], "state" -> Some("NotYetActivated"))

      when(mockHttp.get(equalTo(formatUrl("1234")))(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(NOT_FOUND, activeEnrolment, Map.empty[String, Seq[String]])))
      connector.hasActiveUsers(List("1234"), "someEnrolmentKey").futureValue mustBe false
    }
  }

}
