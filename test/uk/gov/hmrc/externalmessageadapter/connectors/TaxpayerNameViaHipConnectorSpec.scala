/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger as LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.mockito.ArgumentMatchers.{ any, eq as meq }
import org.mockito.Mockito.when
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.slf4j.LoggerFactory
import play.api.http.Status.{ INTERNAL_SERVER_ERROR, NOT_FOUND, NOT_IMPLEMENTED, OK, UNAUTHORIZED }
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.*
import uk.gov.hmrc.domain.SaUtr
import uk.gov.hmrc.externalmessageadapter.MetricOrchestratorStub
import uk.gov.hmrc.common.message.model.TaxpayerName
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse, NotFoundException, StringContextOps }
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator
import uk.gov.hmrc.externalmessageadapter.util.TestData.TEST_TAXPAYER

import scala.concurrent.{ ExecutionContext, Future }

class TaxpayerNameViaHipConnectorSpec
    extends PlaySpec with ScalaFutures with LogCapturing with MockitoSugar with MetricOrchestratorStub
    with IntegrationPatience {

  val fullTaxpayerName: TaxpayerName = TaxpayerName(
    title = Some("Mr"),
    forename = Some("Erbert"),
    secondForename = Some("Donaldson"),
    surname = Some("Ducking"),
    honours = Some("KCBE")
  )

  val utr: SaUtr = SaUtr("12345678990")

  "Parsing from JSON to a" must {

    implicit val headerCarrier: HeaderCarrier = new HeaderCarrier()
    "work for more complete Taxpayer data JSON" in {
      val json = Some(Json.parse("""{
                                   |    "name" : {
                                   |        "title": "Mr",
                                   |        "forename": "Erbert",
                                   |        "secondForename": "Donaldson",
                                   |        "surname": "Ducking",
                                   |        "honours": "KCBE"
                                   |    },
                                   |    "address": {
                                   |        "addressLine1": "42 Somewhere's Street",
                                   |        "addressLine2": "London",
                                   |        "addressLine3": "Greater London",
                                   |        "addressLine4": "",
                                   |        "addressLine5": "",
                                   |        "postcode": "WO9H 8AA",
                                   |        "foreignCountry": null,
                                   |        "returnedLetter": true,
                                   |        "additionalDeliveryInformation": "Leave by door"
                                   |    },
                                   |    "contact": {
                                   |        "telephone": {
                                   |            "daytime": "02654321#1235",
                                   |            "evening": "027123456",
                                   |            "mobile": "07676767",
                                   |            "fax": "0209798969"
                                   |        },
                                   |        "email": {
                                   |            "primary": "erbert@notthere.co.uk"
                                   |        },
                                   |        "other": {}
                                   |    }
                                   |}
                                   | """.stripMargin))

      val result = connectorWithResponse(json).taxpayerName(utr)

      result.futureValue must be(Some(fullTaxpayerName))
    }

    "work for Taxpayer JSON which holds no name field" in {
      val json = Some(Json.parse("""{
                                   |    "address": {
                                   |        "addressLine1": "42 Somewhere's Street",
                                   |        "addressLine2": "London",
                                   |        "addressLine3": "Greater London",
                                   |        "postcode": "WO9H 8AA",
                                   |        "foreignCountry": null,
                                   |        "returnedLetter": true,
                                   |        "additionalDeliveryInformation": "Leave by door"
                                   |    },
                                   |    "contact": {
                                   |        "telephone": {
                                   |            "mobile": "07676767"
                                   |        },
                                   |        "email": {
                                   |            "primary": "erbert@notthere.co.uk"
                                   |        },
                                   |        "other": {}
                                   |    }
                                   |}
                                   | """.stripMargin))

      val result = connectorWithResponse(json).taxpayerName(utr)

      result.futureValue must be(None)
    }

    "work for empty Taxpayer JSON" in {
      val result = connectorWithResponse(Some(Json.parse("{}"))).taxpayerName(utr)
      result.futureValue must be(None)
    }

    "work for JSON which only holds the name data" in {
      val json = Some(Json.parse("""{
                                   |"name" : {
                                   |        "title": "Mr",
                                   |        "forename": "Erbert",
                                   |        "secondForename": "Donaldson",
                                   |        "surname": "Ducking",
                                   |        "honours": "KCBE"
                                   |    }
                                   |}""".stripMargin))

      val result = connectorWithResponse(json).taxpayerName(utr)

      result.futureValue must be(Some(fullTaxpayerName))
    }
  }

  "Taxpayer connector" must {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    "log an error and return empty TaxpayerName on 5** or non 404 4** error" in {
      val logger = play.api.Logger(connector.getClass).underlyingLogger.asInstanceOf[LogbackLogger]
      withCaptureOfLoggingFrom(logger) { logEvents =>
        connectorWithResponse(None, INTERNAL_SERVER_ERROR).taxpayerName(utr).futureValue must be(None)

        connectorWithResponse(None, NOT_IMPLEMENTED).taxpayerName(utr).futureValue must be(None)

        connectorWithResponse(None, UNAUTHORIZED).taxpayerName(utr).futureValue must be(None)

        logEvents.count(_.getLevel == Level.ERROR) must be(3)
      }
    }

    "log an warn level message and return empty TaxpayerName on 404 error" in {
      val logger = play.api.Logger(connector.getClass).underlyingLogger.asInstanceOf[LogbackLogger]
      withCaptureOfLoggingFrom(logger) { logEvents =>
        connectorWithResponse(None, NOT_FOUND).taxpayerName(utr).futureValue must be(None)
        logEvents.head.getLevel must be(Level.WARN)
        logEvents.head.getMessage must include(utr.value)
      }
    }
  }

  "NameFromHods.format" must {
    import NameFromHods.format

    "read the json correctly" in {
      Json.parse(nameFromHodsJsonString).as[NameFromHods] mustBe nameFromHods
    }

    "throw exception for invalid json" in {
      intercept[JsResultException] {
        Json.parse(nameFromHodsInvalidJsonString).as[NameFromHods]
      }
    }

    "write the object correctly" in {
      Json.toJson(nameFromHods) mustBe Json.parse(nameFromHodsJsonString)
    }
  }

  val mockHttp: HttpClientV2 = mock[HttpClientV2]
  val requestBuilder: RequestBuilder = mock[RequestBuilder]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[HttpClientV2].toInstance(mockHttp))
    .configure("metrics.enabled" -> "false")
    .configure("microservice.services.taxpayer-data-hip.enabled" -> true)
    .injector()

  val connector: TaxpayerNameConnector = injector.instanceOf[TaxpayerNameConnector]

  def connectorWithResponse(json: Option[JsValue], status: Int = OK): TaxpayerNameConnector = {
    val nameResponse = json
      .map(js => Future.successful(js.as[NameFromHods]))
      .getOrElse(
        Future.failed(status match {
          case NOT_FOUND => new NotFoundException("test")
          case _         => new RuntimeException("some other error")
        })
      )

    when(
      mockHttp.get(
        meq(url"http://localhost:8091/ods-sa/v1/self-assessment/individual/$utr/designatory-details/taxpayer")
      )(
        any[HeaderCarrier]
      )
    ).thenReturn(requestBuilder)
    when(requestBuilder.setHeader(any)).thenReturn(requestBuilder)
    when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
      .thenReturn(nameResponse)

    connector
  }
}
