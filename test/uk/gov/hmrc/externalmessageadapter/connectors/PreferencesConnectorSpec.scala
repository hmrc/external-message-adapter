/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.{ FORBIDDEN, GATEWAY_TIMEOUT, NOT_FOUND, OK }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.{ Injector, bind }
import play.api.libs.json.{ JsObject, JsResultException, JsString, Json }
import uk.gov.hmrc.common.message.model.{ Regime, TaxEntity }
import uk.gov.hmrc.domain.{ Nino, SaUtr }
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.externalmessageadapter.{ GenerateRandom, MetricOrchestratorStub }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator
import uk.gov.hmrc.externalmessageadapter.util.TestData.TEST_EMAIL_ADDRESS_VALUE

import java.net.URL
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.postfixOps

class PreferencesConnectorSpec
    extends PlaySpec with ScalaFutures with MockitoSugar with MetricOrchestratorStub with EitherValues
    with BeforeAndAfterEach {

  lazy val mockHttp: HttpClientV2 = mock[HttpClientV2]
  val requestBuilder: RequestBuilder = mock[RequestBuilder]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[HttpClientV2].toInstance(mockHttp))
    .configure("metrics.enabled" -> "false")
    .injector()

  lazy val connector: PreferencesConnector =
    injector.instanceOf[PreferencesConnector]

  override def beforeEach(): Unit =
    reset(mockHttp, mockMetricOrchestrator)

  "verifiedEmailAddress in connector" must {
    "return a valid email when a preference is found for sautr" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(
          Future.successful(
            HttpResponse(OK, JsObject(Seq("email" -> JsString("an@email.com"))), Map.empty[String, Seq[String]])
          )
        )

      connector
        .verifiedEmailAddress(MessageFixtures.createTaxEntity(SaUtr("someUtr")))
        .futureValue mustBe EmailValidation("an@email.com")
    }

    "return a valid email when a preference is found for nino" in {
      val nino = GenerateRandom.nino()
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(
          Future.successful(
            HttpResponse(OK, JsObject(Seq("email" -> JsString("an@email.com"))), Map.empty[String, Seq[String]])
          )
        )
      connector.verifiedEmailAddress(MessageFixtures.createTaxEntity(nino)).futureValue mustBe EmailValidation(
        "an@email.com"
      )
    }

    "return VerifiedEmailNotFoundException when a preference is not found" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(
          Future.successful(
            HttpResponse(NOT_FOUND, "not found", Map.empty[String, Seq[String]])
          )
        )

      connector
        .verifiedEmailAddress(MessageFixtures.createTaxEntity(SaUtr("someUtr")))
        .futureValue mustBe VerifiedEmailNotFound("not found")
    }

    "return a OtherException when status is 5xx" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(GATEWAY_TIMEOUT, "")))

      val e = connector.verifiedEmailAddress(MessageFixtures.createTaxEntity(SaUtr("someUtr"))).failed.futureValue
      e mustBe an[OtherException]
      e.getMessage mustBe "OTHER_EXCEPTION_504"
    }

    "return a OtherException when status is 4xx and is not 404" in {
      when(mockHttp.get(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(FORBIDDEN, "")))

      val e = connector.verifiedEmailAddress(MessageFixtures.createTaxEntity(SaUtr("someUtr"))).failed.futureValue
      e mustBe an[OtherException]
      e.getMessage mustBe "OTHER_EXCEPTION_403"
    }
  }

  "verifiedEmailNotFoundException getMessage" must {

    "return 'The backend has rejected the message due to not being able to verify the email address.' when reason code is 'EMAIL_ADDRESS_NOT_VERIFIED'" in {
      val result = VerifiedEmailNotFound("EMAIL_ADDRESS_NOT_VERIFIED").getMessage
      result mustBe "The backend has rejected the message due to not being able to verify the email address."
    }

    "return 'email not verified as user not opted in' when reason code is 'NOT_OPTED_IN'" in {
      val result = VerifiedEmailNotFound("NOT_OPTED_IN").getMessage
      result mustBe "email: not verified as user not opted in"
    }

    "return 'email not verified as preferences not found' when reason code is 'PREFERENCES_NOT_FOUND'" in {
      val result = VerifiedEmailNotFound("PREFERENCES_NOT_FOUND").getMessage
      result mustBe "email: not verified as preferences not found"
    }

    "return 'email not verified for unknown reason' otherwise" in {
      val result = VerifiedEmailNotFound("XXX").getMessage
      result mustBe "email: not verified for unknown reason"
    }
  }

  "VerifiedEmailAddressResponse value" must {
    "return Left for VerifiedEmailNotFound" in {
      val result = VerifiedEmailNotFound("EMAIL_ADDRESS_NOT_VERIFIED").value

      result.left.value mustBe VerifiedEmailNotFound("EMAIL_ADDRESS_NOT_VERIFIED")
    }
  }

  "Mark for de-enrolment" must {

    "succeed in the normal case" in {
      when(mockHttp.put(any)(any)).thenReturn(requestBuilder)
      when(requestBuilder.withBody(any)(any, any, any)).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, "")))

      val taxEntity = TaxEntity(Regime.paye, Nino("ER112233A"))
      val response = connector.markPreferencesForDeEnrolment(taxEntity).futureValue
      response.status mustBe OK

      val s = "http://localhost:8025/preferences/mark-for-de-enrolment?taxRegime=paye&taxId=ER112233A"
      verify(mockHttp).put(ArgumentMatchers.eq(URL(s)))(any)
    }
  }

  "unset de-enrolment" must {
    "succeed in the normal case" in {
      when(mockHttp.put(any)(any)).thenReturn(requestBuilder)
      when(requestBuilder.withBody(any)(any, any, any)).thenReturn(requestBuilder)
      when(requestBuilder.execute[HttpResponse](any[HttpReads[HttpResponse]], any[ExecutionContext]))
        .thenReturn(Future.successful(HttpResponse(OK, "")))

      val taxEntity = TaxEntity(Regime.paye, Nino("ER112233A"))
      val response = connector.unsetMarkForDeEnrolment(taxEntity).futureValue
      response.status mustBe OK

      val s = "http://localhost:8025/preferences/unset-de-enrolment?taxRegime=paye&taxId=ER112233A"
      verify(mockHttp).put(ArgumentMatchers.eq(URL(s)))(any)
    }
  }

  "EmailValidation.format" must {
    import EmailValidation.format

    "read the json correctly" in new Setup {
      Json.parse(emailValidationJsonString).as[EmailValidation] mustBe emailValidation
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(emailValidationInvalidJsonString).as[EmailValidation]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(emailValidation) mustBe Json.parse(emailValidationJsonString)
    }
  }

  trait Setup {
    val emailValidation: EmailValidation = EmailValidation(TEST_EMAIL_ADDRESS_VALUE)

    val emailValidationJsonString: String = """{"email":"test@test.com"}""".stripMargin
    val emailValidationInvalidJsonString: String = """{}""".stripMargin
  }
}
