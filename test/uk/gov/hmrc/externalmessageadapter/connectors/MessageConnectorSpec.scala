/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.Status
import play.api.http.Status.CONFLICT
import play.api.libs.json.Json
import uk.gov.hmrc.externalmessageadapter.util.{ Resources, WithWireMock }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, UpstreamErrorResponse }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class MessageConnectorSpec extends PlaySpec with WithWireMock with ScalaFutures with GuiceOneAppPerSuite {

  override def dependenciesPort: Int =
    app.configuration
      .getOptional[Int]("microservice.services.secure-message.port")
      .getOrElse(throw new Exception("Port missing for message"))

  private val messageRequest = Resources.readJson("messages/controller/v3/NewMessage.json")
  private val messageJson = Json.toJson(messageRequest)

  private val seucreMessageRequest = Resources.readJson("messages/controller/v4/NewMessage.json")
  private val secureMmessageJson = Json.toJson(seucreMessageRequest)

  "Message connector" must {
    "return forward the underlying status" in new TestCase {
      givenThat(
        post(urlEqualTo("/secure-messaging/v4/message"))
          .withRequestBody(equalToJson(secureMmessageJson.toString))
          .willReturn(aResponse().withStatus(Status.CREATED))
      )
      val result: Future[HttpResponse] = connector.postMessage(messageJson)
      Await.result(result, 3.seconds).status mustBe Status.CREATED
    }
    "return Upstream4xxResponse if the underlying status fails" in new TestCase {
      givenThat(
        post(urlEqualTo("/secure-messaging/v4/message"))
          .withRequestBody(equalToJson(secureMmessageJson.toString))
          .willReturn(aResponse().withStatus(CONFLICT))
      )

      val response: Throwable = connector.postMessage(messageJson).failed.futureValue
      response mustBe an[UpstreamErrorResponse]
      response.asInstanceOf[UpstreamErrorResponse].statusCode must be(CONFLICT)
    }
  }

  class TestCase {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]
    val connector: MessageConnector = app.injector.instanceOf[MessageConnector]
  }
}
