/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers

import java.time.LocalDate
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mongodb.scala.bson.ObjectId
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.mvc.Request
import play.api.mvc.Results.Created
import play.api.test.Helpers._
import play.api.test.{ FakeHeaders, FakeRequest, Helpers }
import uk.gov.hmrc.common.message.model._
import uk.gov.hmrc.domain._
import uk.gov.hmrc.externalmessageadapter.connectors.{ AuthIdentifiersConnector, MessageConnector, TaxpayerNameConnector }
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.externalmessageadapter.validators.MessagesUtil
import uk.gov.hmrc.externalmessageadapter.{ GenerateRandom, MetricOrchestratorStub }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator
import uk.gov.hmrc.play.audit.http.connector.{ AuditConnector, AuditResult }
import uk.gov.hmrc.play.audit.model.DataEvent

import scala.concurrent.{ ExecutionContext, Future }

class MessageControllerV3EnrolmentFlagOnSpec
    extends PlaySpec with MockitoSugar with ScalaFutures with MetricOrchestratorStub with IntegrationPatience {

  val mockRepo: MongoMessageRepository = mock[MongoMessageRepository]
  val mockAuthConnector: AuthIdentifiersConnector = mock[AuthIdentifiersConnector]
  val mockAuditConnector: AuditConnector = mock[AuditConnector]
  val mockMessageConnector: MessageConnector = mock[MessageConnector]
  val mockTaxpayerNameConnector: TaxpayerNameConnector = mock[TaxpayerNameConnector]

  val mockMessagesUtil: MessagesUtil = mock[MessagesUtil]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[MongoMessageRepository].toInstance(mockRepo))
    .overrides(bind[AuthIdentifiersConnector].toInstance(mockAuthConnector))
    .overrides(bind[AuditConnector].toInstance(mockAuditConnector))
    .overrides(bind[MessageConnector].toInstance(mockMessageConnector))
    .overrides(bind[MessagesUtil].toInstance(mockMessagesUtil))
    .overrides(bind[TaxpayerNameConnector].toInstance(mockTaxpayerNameConnector))
    .configure(
      "metrics.enabled"           -> "false",
      "quadient.check.enrolments" -> "true"
    )
    .injector()

  lazy val messageController: MessagesController = injector.instanceOf[MessagesController]

  "message - v3 API Email and check enrolments for " must {
    "throw error message when invalid taxid given" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmailAndTaxIDCheck(
        recipient = TaxEntity(Regime.vat, HmrcMtdVat("xxxxxx"), Some("email@test.com")),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234")
      )
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      when(mockMessagesUtil.checkEnrolmentsEnabled).thenReturn(true)
      when(
        mockMessagesUtil
          .checkEnrolmentsAndProcessMessage(any[Message])(any[Request[JsValue]], any[HeaderCarrier])
      )
        .thenReturn(Future.successful(Created(Json.obj("id" -> new ObjectId().toString))))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.CREATED

    }

    "throw error message when the tax Id is not supported and an email is provided" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmailAndTaxIDCheck(
        recipient = TaxEntity(Regime.sa, Vrn("123455789"), Some("email@test.com")),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234")
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"UNKNOWN_TAX_IDENTIFIER","reason":"The backend has rejected the message due to an unknown tax identifier."}"""
      )
    }
  }

  "create a message - v3 API with IssueDate" must {

    "GMC: create message when the checkEnrolment flag is on" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = LocalDate.now.toString
      )
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      when(mockMessagesUtil.checkEnrolmentsEnabled).thenReturn(true)
      when(
        mockMessagesUtil
          .checkEnrolmentsAndProcessMessage(any[Message])(any[Request[JsValue]], any[HeaderCarrier])
      )
        .thenReturn(Future.successful(Created(Json.obj("id" -> new ObjectId().toString))))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      private val result =
        messageController
          .createMessage()(fakeRequest)
          .futureValue

      verify(mockMessagesUtil)
        .checkEnrolmentsAndProcessMessage(any[Message])(any[Request[JsValue]], any[HeaderCarrier])

      result.header.status mustBe Status.CREATED
    }

    "GMC: create message when the checkEnrolment flag is on and skip for email verification for HmrcMtdVat" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.vat, vat, Some("email@test.com")),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = LocalDate.now.toString
      )
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      when(mockMessagesUtil.checkEnrolmentsEnabled).thenReturn(true)
      when(
        mockMessagesUtil
          .checkEnrolmentsAndProcessMessage(any[Message])(any[Request[JsValue]], any[HeaderCarrier])
      )
        .thenReturn(Future.successful(Created(Json.obj("id" -> new ObjectId().toString))))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      private val result =
        messageController
          .createMessage()(fakeRequest)
          .futureValue

      verify(mockMessagesUtil)
        .checkEnrolmentsAndProcessMessage(any[Message])(any[Request[JsValue]], any[HeaderCarrier])

      result.header.status mustBe Status.CREATED
    }

  }

  trait TestCase {
    reset(mockRepo)
    reset(mockAuthConnector)
    reset(mockAuditConnector)
    reset(mockMessageConnector)
    reset(mockTaxpayerNameConnector)
    reset(mockMessagesUtil)

    implicit val headerCarrier: HeaderCarrier = HeaderCarrier()
    val utr: SaUtr = GenerateRandom.utr()
    val vat: HmrcMtdVat = HmrcMtdVat("vat")

    val v3TaxpayerName: TaxpayerName = TaxpayerName(
      title = Some("Dr"),
      forename = Some("Bruce"),
      secondForename = Some("Hulk"),
      surname = Some("Banner"),
      honours = Some("Green"),
      line1 = Some("Line1")
    )
    val taxpayerName = TaxpayerName(line1 = Some("Line1"))
    val taxpayer3Names = TaxpayerName(line1 = Some("Line1"), line2 = Some("Line2"), line3 = Some("Line3"))

    def nullableJsonString(inputVal: String): String =
      Option[String](inputVal).map(input => s""""$input"""").getOrElse("null")

    def messageJsonForV3WithEmailAndTaxIDCheck(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "mdtp"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String = "response-from-customer-advisor",
      subject: String = "Test subject",
      content: String = "PGgyPlRlc3QgY29udGVudDwvaDI+", // => "<h2>Test content</h2>"
      form: String = "SA251",
      validFrom: String = LocalDate.now.toString,
      statutory: Boolean = false,
      paperSent: Boolean = false,
      batchId: Option[String] = None,
      sourceData: String = "eyAibmFtZSIgOiAidmFsdWUiIH0=",
      issueDate: String = LocalDate.now.toString,
      alertQueue: String = "DEFAULT"
    ): JsValue = {

      val jsonString =
        s"""
           | {
           |   "externalRef":{
           |      "id":${nullableJsonString(messageRef.id)},
           |      "source":${nullableJsonString(messageRef.source)}
           |   },
           |   "recipient":{
           |      "taxIdentifier": {
           |          "name":"${recipient.identifier.name}",
           |          "value":"${recipient.identifier.value}"
           |      },
           |      "name":${Json.toJson(recipientName).toString()}
           |      ${recipient.email.map(e => s""", "email":"$e"""").getOrElse("")}
           |   },
           |   "messageType":"$messageType",
           |   "validFrom": "$validFrom",
           |   "subject":"$subject",
           |   "content":"$content",
           |   "alertQueue":"$alertQueue",
           |   "details":{
           |      "formId":"$form",
           |      "issueDate":"$issueDate",
           |      "statutory":$statutory,
           |      "paperSent":$paperSent,
           |      "sourceData": "$sourceData"
           |      ${batchId.map(id => s""", "batchId":"$id" """).getOrElse("")}
           |   }
           |}
         """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }

    def messageJsonForV3WithIssueDate(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "customer-advisors-frontend"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String = "response-from-customer-advisor",
      subject: String = "Test subject",
      content: String = "PGgyPlRlc3QgY29udGVudDwvaDI+", // => "<h2>Test content</h2>"
      form: String = "SA251",
      validFrom: LocalDate = LocalDate.now,
      statutory: Boolean = false,
      paperSent: Boolean = false,
      batchId: Option[String] = None,
      sourceData: String = "eyAibmFtZSIgOiAidmFsdWUiIH0=",
      issueDate: String
    ): JsValue = {

      val jsonString =
        s"""
           | {
           |   "externalRef":{
           |      "id":${nullableJsonString(messageRef.id)},
           |      "source":${nullableJsonString(messageRef.source)}
           |   },
           |   "recipient":{
           |      "taxIdentifier": {
           |          "name":"${recipient.identifier.name}",
           |          "value":"${recipient.identifier.value}"
           |      },
           |      "name":${Json.toJson(recipientName).toString()}
           |      ${recipient.email.map(e => s""", "email":"$e"""").getOrElse("")}
           |   },
           |   "messageType":"$messageType",
           |   "validFrom": "${formatDate(validFrom)}",
           |   "subject":"$subject",
           |   "content":"$content",
           |   "details":{
           |      "formId":"$form",
           |      "issueDate":"$issueDate",
           |      "statutory":$statutory,
           |      "paperSent":$paperSent,
           |      "sourceData": "$sourceData"
           |      ${batchId.map(id => s""", "batchId":"$id" """).getOrElse("")}
           |   }
           |}
    """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }

  }
}
