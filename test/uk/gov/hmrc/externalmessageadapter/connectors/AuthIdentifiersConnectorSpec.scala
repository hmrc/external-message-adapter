/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.http.{ HeaderNames, Status }
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.domain.TaxIds.TaxIdWithName
import uk.gov.hmrc.domain.*
import uk.gov.hmrc.common.message.model.TaxEntity.{ HmceVatdecOrg, HmrcCusOrg, HmrcPptOrg }
import uk.gov.hmrc.externalmessageadapter.util.WithWireMock
import uk.gov.hmrc.http.{ Authorization, HeaderCarrier, UpstreamErrorResponse }

class AuthIdentifiersConnectorSpec
    extends PlaySpec with GuiceOneAppPerSuite with ScalaFutures with WithWireMock with MockitoSugar
    with IntegrationPatience {

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure("metrics.enabled" -> "false")
      .build()

  override def dependenciesPort: Int =
    app.configuration
      .getOptional[Int]("microservice.services.auth.port")
      .getOrElse(throw new Exception("Port missing for Auth"))

  "AuthIdentifiers connector" must {

    "return an empty set if the auth call is not authorised" in new TestCase {
      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.UNAUTHORIZED)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(Set())
    }

    "throw an exception if the auth call fails " in new TestCase {
      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(INTERNAL_SERVER_ERROR)
          )
      )

      val response: Throwable = authConnector.currentEffectiveTaxIdentifiers.failed.futureValue
      response mustBe an[UpstreamErrorResponse]
      response.asInstanceOf[UpstreamErrorResponse].statusCode must be(INTERNAL_SERVER_ERROR)
    }

    "return an empty set if auth returns no identifiers" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": []
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(Set())
    }

    "return an empty set if auth returns an unknown identifier" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "UNKNOWN",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "UTR",
                                   |          "value": "1872796160"
                                   |        }
                                   |      ],
                                   |      "state": "Activated"
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(Set())
    }

    "return a set of CtUtr and nino" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "IR-CT",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "UTR",
                                   |          "value": "1872796160"
                                   |        }
                                   |      ],
                                   |      "state": "Activated"
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(Set(CtUtr("1872796160")))
    }

    "return a set of utr and nino" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "IR-SA",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "UTR",
                                   |          "value": "1872796160"
                                   |        }
                                   |      ],
                                   |      "state": "Activated"
                                   |    },
                                   |    {
                                   |      "key": "HMRC-NI",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "NINO",
                                   |          "value": "CE123456D"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(Set(SaUtr("1872796160"), Nino("CE123456D")))
    }

    "get all val tax ids for if only HMRC-MTD-VAT enrolment " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMRC-MTD-VAT",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "VRN",
                                   |          "value": "example vrn"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcMtdVat("example vrn"), HmceVatdecOrg("example vrn"))
      )
    }

    "get tax ids for HMRC-OBTDS-ORG enrolment " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMRC-OBTDS-ORG",
                                   |      "identifiers": [
                                   |        {
                                   |           "key":"HMRC-OBTDS-ORG",
                                   |           "value":"XZFH00000100024"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcObtdsOrg("XZFH00000100024"))
      )
    }

    "get all val tax ids for if only HMCE-VATDEC-ORG enrolment " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMCE-VATDEC-ORG",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "VATRegNo",
                                   |          "value": "example vrn"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    },
                                   |    {
                                   |      "key": "HMRC-MTD-VAT",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "VRN",
                                   |          "value": "example vrn"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcMtdVat("example vrn"), HmceVatdecOrg("example vrn"))
      )
    }

    "get all val tax ids for if both HMCE-VATDEC-ORG and HMRC-VATDEC-ORG enrolments " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMCE-VATDEC-ORG",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "VATRegNo",
                                   |          "value": "example vrn"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcMtdVat("example vrn"), HmceVatdecOrg("example vrn"))
      )
    }

    "get all val tax ids for only HMRC-CUS-ORG enrolment " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMRC-CUS-ORG",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "EORINumber",
                                   |          "value": "example eori"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcCusOrg("example eori"))
      )
    }

    "get all val tax ids for only HMRC-PPT-ORG enrolment " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMRC-PPT-ORG",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "EtmpRegistrationNumber",
                                   |          "value": "example Etmp Registration Number"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcPptOrg("example Etmp Registration Number"))
      )
    }

    "get all val tax ids for only HMRC-MTD-IT enrolment " in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMRC-MTD-IT",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "MTDITID",
                                   |          "value": "X99999999999"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(
        Set(HmrcMtdItsa("X99999999999"))
      )
    }

    "get tax id for empRef identifier" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "IR-PAYE",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "EMPREF",
                                   |          "value": "840Pd00123456"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      val identifier: TaxIdWithName = authConnector.currentEffectiveTaxIdentifiers.futureValue.head
      identifier.name must be("EMPREF")
      identifier.value must be("840Pd00123456")
    }

    "return empty set when given an enrolment with multiple non empRef identifiers" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "IR-PAYE",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "fakeKey1",
                                   |          "value": "666"
                                   |        },
                                   |        {
                                   |          "key": "fakeKey2",
                                   |          "value": "something"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.currentEffectiveTaxIdentifiers.futureValue must be(Set.empty)
    }

    "isStrideUser return Future when the call to authorise succeed" in new TestCase {
      val responseBody: String = """
                                   |{
                                   |  "allEnrolments": [
                                   |    {
                                   |      "key": "HMRC-MTD-IT",
                                   |      "identifiers": [
                                   |        {
                                   |          "key": "MTDITID",
                                   |          "value": "X99999999999"
                                   |        }
                                   |      ],
                                   |      "state": "Activated",
                                   |      "confidenceLevel": 200
                                   |    }
                                   |  ]
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.isStrideUser.futureValue mustBe true
    }

    "isStrideUser return Future when the call to authorise fails" in new TestCase {

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.UNAUTHORIZED)
          )
      )

      authConnector.isStrideUser.futureValue mustBe false
    }

    "strideUserId returns providerId when the call to authorise succeeds" in new TestCase {

      val responseBody: String = """
                                   |{
                                   |  "optionalCredentials": {
                                   |    "providerId": "providerId",
                                   |    "providerType": "providerType"
                                   |  }
                                   |}
                         """.stripMargin

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.strideUserId.futureValue must contain("providerId")
    }

    "strideUserId returns None when the call to authorise succeeds but with credentials missing" in new TestCase {
      val responseBody: String = "{}"

      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.OK)
              .withBody(responseBody)
          )
      )

      authConnector.strideUserId.futureValue mustBe None
    }

    "strideUserId returns None when the call to authorise fails" in new TestCase {
      givenThat(
        post(urlEqualTo("/auth/authorise"))
          .withHeader(HeaderNames.AUTHORIZATION, equalTo(authToken))
          .willReturn(
            aResponse()
              .withStatus(Status.UNAUTHORIZED)
          )
      )

      authConnector.strideUserId.futureValue mustBe None
    }

  }

  trait TestCase {

    val authToken = "authToken23432"
    implicit val hc: HeaderCarrier = HeaderCarrier(authorization = Some(Authorization(authToken)))

    lazy val authConnector: AuthIdentifiersConnector =
      app.injector.instanceOf[AuthIdentifiersConnector]
  }

}
