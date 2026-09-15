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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{ JsObject, JsString }
import play.api.mvc.{ Headers, RequestHeader }
import play.api.test.FakeRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.hooks.Data
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.bootstrap.config.{ ControllerConfig, ControllerConfigs, DefaultHttpAuditEvent }
import uk.gov.hmrc.play.bootstrap.filters.Details

import scala.concurrent.ExecutionContext.Implicits.global

class AuditFilterSpec extends PlaySpec with ScalaFutures with MockitoSugar {
  implicit val hc: HeaderCarrier = HeaderCarrier()
  val configuration: Configuration = Configuration(
    "microservice.services.customs-data-store.host" -> "host",
    "bootstrap.auditing.maxBodyLength"              -> 32665
  )
  val controllerConfigs = ControllerConfigs(Map("testController" -> ControllerConfig()))
  val auditConnector = mock[AuditConnector]
  val httpAuditEvent = new DefaultHttpAuditEvent("test")
  val materializer: Materializer = NoMaterializer

  trait RequestDetails {
    def getBuildRequestDetails(requestHeader: RequestHeader, requestBody: Data[String]): Details = ???
  }

  val auditFilter = new AuditFilter(configuration, controllerConfigs, auditConnector, httpAuditEvent, materializer)
    with RequestDetails {
    override def getBuildRequestDetails(requestHeader: RequestHeader, requestBody: Data[String]): Details =
      buildRequestDetails(requestHeader, requestBody)
  }

  "AuditFilter" must {
    "extendedDataEvent should include correlationId and requestBody" in {
      val headers = Headers("requestBody" -> "test-body", "CorrelationId" -> "test-correlationId").headers
      val requestDetails: JsObject = JsObject(
        headers.map { case (key, value) => key -> JsString(value) }
      )

      val event = auditFilter.extendedDataEvent(
        "test",
        "test-transaction",
        FakeRequest("GET", "/test"),
        requestDetails
      )
      val details = event.detail.as[Map[String, String]]
      details must contain key "CorrelationId"
      details("CorrelationId") mustBe "test-correlationId"
      details must contain key "requestBody"
      details("requestBody") mustBe "test-body"
    }

    "buildRequestDetails should generate details with correlationId and requestBody from the given request header" in {
      val request = FakeRequest(
        "GET",
        "/test",
        Headers("requestBody" -> "test-body", "CorrelationId" -> "test-correlationId"),
        "body"
      )

      val requestDetails = auditFilter.getBuildRequestDetails(request, Data("test-body", false, false))
      val details = requestDetails.details.as[Map[String, String]]
      details must contain key "CorrelationId"
      details("CorrelationId") mustBe "test-correlationId"
      details must contain key "requestBody"
      details("requestBody") mustBe "test-body"
    }
  }
}
