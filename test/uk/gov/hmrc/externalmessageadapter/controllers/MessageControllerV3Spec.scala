/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers

import org.apache.commons.codec.binary.Base64
import java.time.LocalDate
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test.{ FakeHeaders, FakeRequest, Helpers }
import uk.gov.hmrc.domain.TaxIds.TaxIdWithName
import uk.gov.hmrc.domain._
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.play.audit.http.connector.{ AuditConnector, AuditResult }
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.externalmessageadapter.{ GenerateRandom, MetricOrchestratorStub }
import uk.gov.hmrc.externalmessageadapter.connectors._
import uk.gov.hmrc.common.message.model._
import uk.gov.hmrc.common.message.model.TaxEntity._
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.externalmessageadapter.util.Resources
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random

class MessageControllerV3Spec
    extends PlaySpec with MockitoSugar with ScalaFutures with MetricOrchestratorStub with IntegrationPatience {

  val mockRepo = mock[MongoMessageRepository]
  val mockAuthConnector = mock[AuthIdentifiersConnector]
  val mockAuditConnector = mock[AuditConnector]
  val mockMessageConnector = mock[MessageConnector]
  val mockPreferencesConnector = mock[PreferencesConnector]
  val mockTaxpayerNameConnector = mock[TaxpayerNameConnector]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[MongoMessageRepository].toInstance(mockRepo))
    .overrides(bind[AuthIdentifiersConnector].toInstance(mockAuthConnector))
    .overrides(bind[AuditConnector].toInstance(mockAuditConnector))
    .overrides(bind[MessageConnector].toInstance(mockMessageConnector))
    .overrides(bind[PreferencesConnector].toInstance(mockPreferencesConnector))
    .overrides(bind[TaxpayerNameConnector].toInstance(mockTaxpayerNameConnector))
    .configure(
      "metrics.enabled"           -> "false",
      "quadient.check.enrolments" -> "false"
    )
    .injector()

  lazy val messageController: MessagesController = injector.instanceOf[MessagesController]

  "createMessage method" must {
    "send a truncated TxSucceeded audit event for a message with large content" in new TestCase {

      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3(
        messageType = "response-from-customer-advisor",
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        content = "a" * 128001,
        sourceData = Some("b" * 128001),
        emailAlertEventUrl = Some("/someUrl")
      )
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockPreferencesConnector.verifiedEmailAddress(any[TaxEntity])(any[HeaderCarrier]))
        .thenReturn(Future.successful(EmailValidation("some@email.com")))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val dataEvent = dataEventCaptor.getValue

      dataEvent.detail("originalRequest") mustBe
        Json.stringify(
          newMessage.as[JsObject]
            ++ Json.obj("sourceData" -> "sourceData is removed to reduce size")
            ++ Json.obj("content" -> "content is removed to reduce size")
        )
    }
    "send a  TxSucceeded audit event with replaced originalRequest if it fails to truncate" in new TestCase {

      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3(
        messageType = "response-from-customer-advisor",
        recipient = TaxEntity(Regime.sa, utr),
        subject = "s" * 128001,
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        content = "a" * 128001,
        sourceData = Some("b"),
        emailAlertEventUrl = Some("/someUrl")
      )
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockPreferencesConnector.verifiedEmailAddress(any[TaxEntity])(any[HeaderCarrier]))
        .thenReturn(Future.successful(EmailValidation("some@email.com")))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val dataEvent = dataEventCaptor.getValue

      dataEvent.detail("originalRequest") mustBe
        "request is too big even without content and sourceData"
    }
  }

  "create a message - v3 API" must {
    "create, persist in DB and audit using the new message format" in new TestCase {
      private val newMessage = Resources.readJson("messages/controller/v3/SA316.json")
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      private val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")
      private val utrValue = "1000050548"
      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "response-from-customer-advisor",
        "formId"          -> "SA316",
        "batchId"         -> "1234",
        "threadId"        -> dataEvent.detail("threadId"),
        "sautr"           -> utrValue,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "newMessageAlert_SA316"
      storedMessage.recipient.regime mustBe Regime.sa
      storedMessage.recipient.identifier.name mustBe "sautr"
      storedMessage.recipient.identifier.value mustBe utrValue
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.body.flatMap(_.form).get mustBe "SA316"
      storedMessage.body.flatMap(_.`type`).get mustBe "response-from-customer-advisor"
      storedMessage.body.flatMap(_.paperSent).get mustBe false
      storedMessage.body.flatMap(_.batchId).get mustBe "1234"
      storedMessage.externalRef.get.id mustBe "abcd1234"
      storedMessage.externalRef.get.source mustBe "some-source"
      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
      storedMessage.emailAlertEventUrl.get mustBe "/someUrl"
    }

    "cannot create gmc message without details field in message" in new TestCase {
      val externalRef = ExternalRef(
        id = "abcd1234",
        source = "gmc"
      )

      val newMessage = messageJsonForV3InvalidGmc(
        messageRef = externalRef,
        recipient = TaxEntity(Regime.sa, utr)
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      val result = messageController.createMessage()(fakeRequest1)
      status(result) mustBe 400
      contentAsString(result) must equal(
        """{"failureId":"MISSING_DETAILS","reason":"details: details not provided where it is required"}"""
      )
    }

    "cannot create gmc message with invalid alertDetails" in new TestCase {
      val newMessage = Json.parse(messageWithInvalidAlertDetails)

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      val result = messageController.createMessage()(fakeRequest1)
      status(result) mustBe 400
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"sourceData: invalid source data provided"}"""
      )
    }

    "create and persist a message from FHDDS" in new TestCase {
      val taxId = HmrcObtdsOrg("XZFH00000100024")

      val newMessage = Resources.readJson("messages/controller/v3/FHDDS.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "fhddsAlertMessage",
        "formId"          -> "",
        "HMRC-OBTDS-ORG"  -> taxId.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "fhddsAlertMessage"
      storedMessage.recipient.regime mustBe Regime.fhdds
      storedMessage.recipient.identifier.name mustBe "HMRC-OBTDS-ORG"
      storedMessage.recipient.identifier.value mustBe "XZFH00000100024"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "abcd1234"
      storedMessage.externalRef.get.source mustBe "sees"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message from SDIL with AlertDetails and regime" in new TestCase {
      val taxId = HmrcObtdsOrg("XZSD00000100024")

      val newMessage = Resources.readJson("messages/controller/v3/SDIL.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "sdAlertMessage",
        "formId"          -> "",
        "HMRC-OBTDS-ORG"  -> taxId.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "sdAlertMessage"
      storedMessage.alertDetails.data mustBe Map(
        "subject" -> "Test subject",
        "email"   -> "test@test.com",
        "date"    -> s"${LocalDate.now}",
        "key 1"   -> "value 1",
        "key2"    -> "value2"
      )

      storedMessage.recipient.regime mustBe Regime.sdil
      storedMessage.recipient.identifier.name mustBe "HMRC-OBTDS-ORG"
      storedMessage.recipient.identifier.value mustBe "XZSD00000100024"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "abcd1234"
      storedMessage.externalRef.get.source mustBe "mdtp"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message with the HMRC-MTD-VAT reference and identifier " in new TestCase {

      private val vatValue = "123 4567 89"

      val newMessage = Resources.readJson("messages/controller/v3/HmrcMtdVat.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "mtdfb_vat_principal_sign_up_successful",
        "formId"          -> "",
        "HMRC-MTD-VAT"    -> vatValue,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "mtdfb_vat_principal_sign_up_successful"
      storedMessage.recipient.regime mustBe Regime.vat
      storedMessage.recipient.identifier.name mustBe "HMRC-MTD-VAT"
      storedMessage.recipient.identifier.value mustBe vatValue
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "mdtp"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message with the HMRC-MTD-VAT.VRN reference and identifier " in new TestCase {

      private val vatValue = "123456789"

      val newMessage = Resources.readJson("messages/controller/v3/HmrcMtdVatVrn.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "mtdfb_vat_principal_sign_up_successful",
        "formId"          -> "",
        "vrn"             -> vatValue,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "mtdfb_vat_principal_sign_up_successful"
      storedMessage.recipient.regime mustBe Regime.vat
      storedMessage.recipient.identifier.name mustBe "vrn"
      storedMessage.recipient.identifier.value mustBe vatValue
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "mdtp"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message with the EPAYE reference and identifier " in new TestCase {

      val taxId = Epaye("840Pd00123456")

      val newMessage = Resources.readJson("messages/controller/v3/EPAYE.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "mtdfb_vat_principal_sign_up_successful",
        "formId"          -> "",
        "EMPREF"          -> taxId.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "mtdfb_vat_principal_sign_up_successful"
      storedMessage.recipient.regime mustBe Regime.epaye
      storedMessage.recipient.identifier.name mustBe "EMPREF"
      storedMessage.recipient.identifier.value mustBe "840Pd00123456"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "mdtp"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message with the HMCE-VATDEC-ORG reference and identifier " in new TestCase {
      val taxId = HmceVatdecOrg("1234567890")

      val newMessage = Resources.readJson("messages/controller/v3/HmceVatdecOrg.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "mtdfb_vat_principal_sign_up_successful",
        "formId"          -> "",
        "HMCE-VATDEC-ORG" -> taxId.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "mtdfb_vat_principal_sign_up_successful"
      storedMessage.recipient.regime mustBe Regime.vat
      storedMessage.recipient.identifier.name mustBe "HMCE-VATDEC-ORG"
      storedMessage.recipient.identifier.value mustBe "1234567890"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "mdtp"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message with the HMRC-CUS-ORG reference and identifier " in new TestCase {
      val taxId = HmrcCusOrg("GB1234567890")

      val newMessage = Resources.readJson("messages/controller/v3/HmcrCusOrg.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"        -> storedMessage.id.toString,
        "messageType"      -> "cds_ddi_setup_dcs_alert",
        "formId"           -> "",
        "HMRC-CUS-ORG"     -> taxId.value,
        "originalRequest"  -> Json.stringify(newMessage),
        "notificationType" -> "Direct Debit"
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "cds_ddi_setup_dcs_alert"
      storedMessage.recipient.regime mustBe Regime.cds
      storedMessage.recipient.identifier.name mustBe "HMRC-CUS-ORG"
      storedMessage.recipient.identifier.value mustBe "GB1234567890"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "mdtp"
      storedMessage.tags.getOrElse(Map()).getOrElse("notificationType", "") mustBe "Direct Debit"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create and persist a message with the HMRC-PPT-ORG reference and identifier " in new TestCase {
      val taxId = HmrcPptOrg("XMPPT0000000001")

      val newMessage = Resources.readJson("messages/controller/v3/HmrcPptOrg.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"              -> storedMessage.id.toString,
        "messageType"            -> "ppt_ddi_setup_dcs_alert",
        "formId"                 -> "",
        "ETMPREGISTRATIONNUMBER" -> taxId.value,
        "originalRequest"        -> Json.stringify(newMessage),
        "notificationType"       -> "Direct Debit"
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "ppt_ddi_setup_dcs_alert"
      storedMessage.recipient.regime mustBe Regime.ppt
      storedMessage.recipient.identifier.name mustBe "ETMPREGISTRATIONNUMBER"
      storedMessage.recipient.identifier.value mustBe "XMPPT0000000001"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "mdtp"
      storedMessage.tags.getOrElse(Map()).getOrElse("notificationType", "") mustBe "Direct Debit"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create a message with the HMRC-PODS-ORG.PSAID identifier" in new TestCase {
      val taxId = HmrcPodsOrg("A2100006")

      val newMessage = Resources.readJson("messages/controller/v3/HmrcPodsOrg.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"        -> storedMessage.id.toString,
        "messageType"      -> "ppt_ddi_setup_dcs_alert",
        "formId"           -> "",
        "PSAID"            -> taxId.value,
        "originalRequest"  -> Json.stringify(newMessage),
        "notificationType" -> "Direct Debit"
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "ppt_ddi_setup_dcs_alert"
      storedMessage.recipient.regime mustBe Regime.pods
      storedMessage.recipient.identifier.name mustBe "PSAID"
      storedMessage.recipient.identifier.value mustBe "A2100006"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "external-message-adapter"
      storedMessage.tags.getOrElse(Map()).getOrElse("notificationType", "") mustBe "Direct Debit"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create a message with the HMRC-PODSPP-ORG.PSPID identifier" in new TestCase {
      val taxId = HmrcPodsPpOrg("A2100008")

      val newMessage = Resources.readJson("messages/controller/v3/HmrcPodsPpOrg.json")

      val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 = FakeRequest(
        Helpers.POST,
        routes.MessagesController.createMessage().url,
        FakeHeaders(),
        Json.toJson(newMessage)
      )

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      val storedMessage = messageCaptor.getValue
      val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")

      dataEvent.detail mustBe Map(
        "messageId"        -> storedMessage.id.toString,
        "messageType"      -> "ppt_ddi_setup_dcs_alert",
        "formId"           -> "",
        "PSPID"            -> taxId.value,
        "originalRequest"  -> Json.stringify(newMessage),
        "notificationType" -> "Direct Debit"
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "ppt_ddi_setup_dcs_alert"
      storedMessage.recipient.regime mustBe Regime.pods
      storedMessage.recipient.identifier.name mustBe "PSPID"
      storedMessage.recipient.identifier.value mustBe "A2100008"
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.externalRef.get.id mustBe "123456789123456789"
      storedMessage.externalRef.get.source mustBe "external-message-adapter"
      storedMessage.tags.getOrElse(Map()).getOrElse("notificationType", "") mustBe "Direct Debit"

      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "look up taxpayer name if none is supplied in input" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = None
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])
      when(mockTaxpayerNameConnector.taxpayerName(any[SaUtr])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Some(v3TaxpayerName)))
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
      verify(mockTaxpayerNameConnector, times(1)).taxpayerName(any[SaUtr])(any[HeaderCarrier])

      private val storedMessage = messageCaptor.getValue

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
    }

    "do not look up taxpayer name if one is supplied in input" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = Some(v3TaxpayerName)
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
      verify(mockTaxpayerNameConnector, never()).taxpayerName(any[SaUtr])(any[HeaderCarrier])

      private val storedMessage = messageCaptor.getValue

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
    }

    "handle failure in taxpayer name lookup and save message with no name" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = None
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockTaxpayerNameConnector.taxpayerName(any[SaUtr])(any[HeaderCarrier])).thenReturn(Future.successful(None))
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      private val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
      verify(mockTaxpayerNameConnector, times(1)).taxpayerName(any[SaUtr])(any[HeaderCarrier])

      private val storedMessage = messageCaptor.getValue

      storedMessage.alertDetails.recipientName mustBe None
    }

    "increment the metrics for every message created" in new TestCase {
      private val message1 = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        form = "SA300"
      )
      private val message2 = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        form = "R002A"
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(message1))
      await(messageController.createMessage()(fakeRequest1))

      val fakeRequest2 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(message2))
      await(messageController.createMessage()(fakeRequest2))
    }

    "create and persist only one record for duplicate message" in new TestCase {
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])
      private val validFrom = LocalDate.now()
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val message = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        messageRef = externalRef,
        form = "SA300",
        validFrom = validFrom
      )
      private val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), message)

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(false))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      await(messageController.createMessage()(fakeRequest))

      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxFailed"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Duplicated")
      dataEvent.detail("messageType") mustBe "response-from-customer-advisor"
      dataEvent.detail("formId") mustBe "SA300"
      dataEvent.detail("sautr") mustBe utr.value
      dataEvent.detail("originalRequest") mustBe Json.stringify(message)
    }

    "reject a message with empty externalRef id or source" in new TestCase {
      val brokenExternalRefs = List(
        ExternalRef(id = "", source = "some-source"),
        ExternalRef(id = "some-id", source = ""),
        ExternalRef(id = "", source = ""),
        ExternalRef(id = null, source = "some-source"),
        ExternalRef(id = "some-id", source = null),
        ExternalRef(id = null, source = null),
        ExternalRef(id = "", source = null),
        ExternalRef(id = null, source = "")
      )

      brokenExternalRefs.foreach {
        testing
      }

      private def testing(testRef: ExternalRef): Unit = {
        val message = messageJsonForV3(
          recipient = TaxEntity(Regime.sa, utr),
          messageType = "response-from-customer-advisor",
          messageRef = testRef
        )
        when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
          .thenReturn(Future.successful(AuditResult.Success))
        val fakeRequest =
          FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), message)
        val result = messageController.createMessage()(fakeRequest)
        status(result) mustBe Status.BAD_REQUEST
        verifyNoInteractions(mockRepo)
      }
    }
  }

  "create a message - v3 API with AlertQueue" must {
    "create, persist in DB and audit using the new message format for BACKGROUND alertQueue" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3WithAlertQueue(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        alertQueue = Some("BACKGROUND")
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      private val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")
      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "response-from-customer-advisor",
        "formId"          -> "SA316",
        "batchId"         -> "1234",
        "threadId"        -> dataEvent.detail("threadId"),
        "sautr"           -> utr.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "newMessageAlert_SA316"
      storedMessage.recipient.regime mustBe Regime.sa
      storedMessage.recipient.identifier.name mustBe "sautr"
      storedMessage.recipient.identifier.value mustBe utr.value
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.body.flatMap(_.form).get mustBe "SA316"
      storedMessage.body.flatMap(_.`type`).get mustBe "response-from-customer-advisor"
      storedMessage.body.flatMap(_.paperSent).get mustBe false
      storedMessage.body.flatMap(_.batchId).get mustBe "1234"
      storedMessage.externalRef.get.id mustBe "abcd1234"
      storedMessage.externalRef.get.source mustBe "some-source"
      storedMessage.alertQueue.get mustBe "BACKGROUND"
      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create, persist in DB and audit using the new message format without alertQueue" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3WithAlertQueue(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234")
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      private val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")
      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "response-from-customer-advisor",
        "formId"          -> "SA316",
        "batchId"         -> "1234",
        "threadId"        -> dataEvent.detail("threadId"),
        "sautr"           -> utr.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "newMessageAlert_SA316"
      storedMessage.recipient.regime mustBe Regime.sa
      storedMessage.recipient.identifier.name mustBe "sautr"
      storedMessage.recipient.identifier.value mustBe utr.value
      storedMessage.content.get mustBe "<h2>Test content</h2>"
      storedMessage.body.flatMap(_.form).get mustBe "SA316"
      storedMessage.body.flatMap(_.`type`).get mustBe "response-from-customer-advisor"
      storedMessage.body.flatMap(_.paperSent).get mustBe false
      storedMessage.body.flatMap(_.batchId).get mustBe "1234"
      storedMessage.externalRef.get.id mustBe "abcd1234"
      storedMessage.externalRef.get.source mustBe "some-source"
      storedMessage.alertQueue.get mustBe "DEFAULT"
      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "create, persist in DB and audit using the new message format with INVALID alertQueue" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "some-source")
      private val newMessage = messageJsonForV3WithAlertQueue(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        alertQueue = Some("INVALID")
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.BAD_REQUEST
      verifyNoInteractions(mockRepo)
    }

  }

  "create a message - v3 API with GMC sourceData" must {
    "create, persist in DB and audit using the new message format with valid sourceData" in new TestCase {
      private val newMessage = Resources.readJson("messages/controller/v3/GMC.json")
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      storedMessage.sourceData mustBe Some("abc")

    }

    "create, persist in DB and audit using the new message format with valid sourceData and content larger than 100KB" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        subject = "Very large message",
        content = largeBase64EncodedContent(),
        sourceData = "abc",
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234")
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      messageController.createMessage()(fakeRequest1).futureValue
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      storedMessage.sourceData mustBe Some("abc")

    }

    "create, persist in DB and audit using the new message format with invalid sourceData" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        sourceData = "&&",
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
      contentAsString(result) must include(""""sourceData: invalid source data provided"""")

    }

  }

  "create a message - v3 API with MDTP sourceData" must {
    "create, persist in DB and audit using the new message format with valid sourceData" in new TestCase {
      private val newMessage = Resources.readJson("messages/controller/v3/MDTP.json")
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      storedMessage.sourceData mustBe Some("abc")

    }

    "create, persist in DB and audit using the new message format with invalid sourceData" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        sourceData = "&&",
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
      contentAsString(result) must include("""sourceData: invalid source data provided"""")

    }

  }

  "create a message - v3 GMC messages" must {
    "create, persist in DB and audit using the new message format" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val contentWithScriptBlock =
        "PGgxPlRlc3QgY29udGVudDwvaDE+IDxzY3JpcHQ+d2luZG93LmFsZXJ0KCJoZWxsbyIpPC9zY3JpcHQ+IDxwPmJvZHk8L3A+"
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        content = contentWithScriptBlock,
        form = "SA316",
        batchId = Some("1234"),
        sourceData = "abc"
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      private val fakeRequest1 =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest1))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      private val dataEvent = dataEventCaptor.getValue

      dataEvent.auditSource mustBe "external-message-adapter"
      dataEvent.auditType mustBe "TxSucceeded"
      dataEvent.eventId must not be empty
      dataEvent.generatedAt must not be null
      dataEvent.tags mustBe Map("transactionName" -> "Message Created")
      dataEvent.detail mustBe Map(
        "messageId"       -> storedMessage.id.toString,
        "messageType"     -> "mailout-batch",
        "formId"          -> "SA316",
        "batchId"         -> "1234",
        "threadId"        -> dataEvent.detail("threadId"),
        "sautr"           -> utr.value,
        "originalRequest" -> Json.stringify(newMessage)
      )

      storedMessage.alertDetails.recipientName mustBe defined
      storedMessage.alertDetails.recipientName.get mustBe v3TaxpayerName
      storedMessage.alertDetails.templateId mustBe "newMessageAlert_SA316"
      storedMessage.recipient.regime mustBe Regime.sa
      storedMessage.recipient.identifier.name mustBe "sautr"
      storedMessage.recipient.identifier.value mustBe utr.value
      storedMessage.content.get mustBe "<h1>Test content</h1>  <p>body</p>"
      storedMessage.body.flatMap(_.form).get mustBe "SA316"
      storedMessage.body.flatMap(_.`type`).get mustBe "mailout-batch"
      storedMessage.body.flatMap(_.paperSent).get mustBe false
      storedMessage.body.flatMap(_.batchId).get mustBe "1234"
      storedMessage.externalRef.get.id mustBe "abcd1234"
      storedMessage.externalRef.get.source mustBe "gmc"
      storedMessage.renderUrl.service mustBe "external-message-adapter"
      storedMessage.renderUrl.url mustBe s"/external-message-adapter/external/messages/${storedMessage.id.toString}/content"
    }

    "reject a GMC message if it does not include any source data" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val contentWithScriptBlock =
        "PGgxPlRlc3QgY29udGVudDwvaDE+IDxzY3JpcHQ+d2luZG93LmFsZXJ0KCJoZWxsbyIpPC9zY3JpcHQ+IDxwPmJvZHk8L3A+"
      private val newMessage = messageJsonForV3(
        recipient = TaxEntity(Regime.sa, utr),
        messageType = "response-from-customer-advisor",
        messageRef = externalRef,
        content = contentWithScriptBlock,
        form = "SA316",
        batchId = Some("1234")
      )
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      private val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), newMessage)
      private val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include("""{"failureId":"INVALID_PAYLOAD","reason":"Invalid Message"}""")
    }

    "reject a GMC message if it does not recognise the tax identifier name" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val contentWithScriptBlock =
        "PGgxPlRlc3QgY29udGVudDwvaDE+IDxzY3JpcHQ+d2luZG93LmFsZXJ0KCJoZWxsbyIpPC9zY3JpcHQ+IDxwPmJvZHk8L3A+"
      private val standardMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        sourceData = "abc",
        messageRef = externalRef,
        content = contentWithScriptBlock,
        form = "SA316",
        batchId = Some("1234")
      )
      private val replacement = (__ \ "name").json.put(JsString("other"))
      private val adjustment = (__ \ "recipient" \ "taxIdentifier").json.update(replacement)
      private val newMessage = standardMessage.transform(adjustment).get
      private val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), newMessage)
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      private val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"UNKNOWN_TAX_IDENTIFIER","reason":"The backend has rejected the message due to an unknown tax identifier."}"""
      )
    }

    "creating a duplicate GMC message must respond with a 409 with the message Duplicate and audit the duplicate" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val contentWithScriptBlock =
        "PGgxPlRlc3QgY29udGVudDwvaDE+IDxzY3JpcHQ+d2luZG93LmFsZXJ0KCJoZWxsbyIpPC9zY3JpcHQ+IDxwPmJvZHk8L3A+"
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        sourceData = "abc",
        messageRef = externalRef,
        content = contentWithScriptBlock,
        form = "SA316",
        batchId = Some("1234")
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(false))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      private val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.CONFLICT
      contentAsString(result) must include(
        """The backend has rejected the message due to duplicated message content or external reference ID."""
      )
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
    }

    "increment stats for duplicate GMC message" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val contentWithScriptBlock =
        "PGgxPlRlc3QgY29udGVudDwvaDE+IDxzY3JpcHQ+d2luZG93LmFsZXJ0KCJoZWxsbyIpPC9zY3JpcHQ+IDxwPmJvZHk8L3A+"
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        sourceData = "abc",
        messageRef = externalRef,
        content = contentWithScriptBlock,
        form = "SA316",
        batchId = Some("1234")
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(false))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      private val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      await(messageController.createMessage()(fakeRequest))

      //      verify(mockStatsMetricRepo).incrementDuplicate(is("gmc"))(any[ExecutionContext])
    }
  }

  "create a message - v3 API with IssueDate" must {

    "MDTP: create and persist message in DB using the new message format if 'validFrom = IssueDate'" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = LocalDate.now.toString
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      await(messageController.createMessage()(fakeRequest))
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
      private val result = messageController.createMessage()(fakeRequest)

      private val storedMessage = messageCaptor.getValue
      status(result) mustBe Status.CREATED
      storedMessage.body.flatMap(_.issueDate) mustBe Some(LocalDate.now)
    }

    "GMC: create and persist message in DB using the new message format if 'validFrom = IssueDate'" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = LocalDate.now.toString
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      private val result = messageController.createMessage()(fakeRequest).futureValue

      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      result.header.status mustBe Status.CREATED
      storedMessage.body.flatMap(_.issueDate) mustBe Some(LocalDate.now)
    }

    "GMC: create and persist message in DB using the new message format if 'validFrom > IssueDate'" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now.plusDays(1),
        issueDate = LocalDate.now.toString
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      private val result = messageController.createMessage()(fakeRequest).futureValue

      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      result.header.status mustBe Status.CREATED
      storedMessage.body.flatMap(_.issueDate) mustBe Some(LocalDate.now)
    }

    "GMC: persist message in DB with validFrom equal to issueDate if 'validFrom < IssueDate' in the request" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = LocalDate.now.plusDays(1).toString
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))
      private val result = messageController.createMessage()(fakeRequest).futureValue

      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      result.header.status mustBe Status.CREATED
      storedMessage.body.flatMap(_.issueDate) mustBe Some(LocalDate.now.plusDays(1))
      storedMessage.validFrom mustBe (LocalDate.now.plusDays(1))
    }

    "GMC: does not create and persist message in DB using the new message format if invalid IssueDate format given" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = "2018/08/04"
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"Invalid date format provided"}"""
      )
    }

    "GMC: does not create and persist message in DB using the new message format if empty IssueDate given" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3WithIssueDate(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now,
        issueDate = ""
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"Invalid date format provided"}"""
      )
    }

    "GMC: create and persist message in DB using the new message format if IssueDate not provided" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "gmc")
      private val newMessage = messageJsonForV3GMC(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        sourceData = "somedata",
        form = "SA316",
        batchId = Some("1234"),
        validFrom = LocalDate.now
      )
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])
      private val dataEventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)
      status(result) mustBe Status.CREATED
      verify(mockRepo).insertIfUnique(messageCaptor.capture())
      verify(mockAuditConnector).sendEvent(dataEventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])

      private val storedMessage = messageCaptor.getValue
      status(result) mustBe Status.CREATED
      storedMessage.body.flatMap(_.issueDate) mustBe Some(LocalDate.now)

    }
  }

  "message - v3 API Empty Fields Check" must {
    "throw error message with empty spaces emailAddress" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        emailAddress = "   "
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"EMAIL_NOT_VERIFIED","reason":"email: invalid email address provided"}"""
      )
    }

    "throw error message with empty emailAddress" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        emailAddress = ""
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"EMAIL_NOT_VERIFIED","reason":"email: invalid email address provided"}"""
      )
    }

    "throw error message with empty alertQueue" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        alertQueue = ""
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"alertQueue: invalid alert queue provided"}"""
      )
    }

    "throw error message with empty sourceData" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        sourceData = ""
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"sourceData: invalid source data provided"}"""
      )
    }

    "throw error message with empty issueDate" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        issueDate = ""
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"Invalid date format provided"}"""
      )
    }

    "throw error message with empty validFrom" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = ""
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"Invalid date format provided"}"""
      )
    }

    "throw error message with invalid email address" in new TestCase {

      val gmcTaxPayerName = TaxpayerName(line1 = Some("Line1"), line2 = Some("  "), line3 = Some(""))
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithName(
        recipient = TaxEntity(Regime.sa, utr, Some("test$test.com")),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = Some(gmcTaxPayerName)
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"EMAIL_NOT_VERIFIED","reason":"email: invalid email address provided"}"""
      )
    }
  }

  "message - v3 API TaxPayer Name" must {
    "create and persist the new message format with TaxPayerName(line1) to DB" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithName(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234")
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.CREATED
      verify(mockRepo).insertIfUnique(messageCaptor.capture())

      private val storedMessage = messageCaptor.getValue
      storedMessage.alertDetails.recipientName mustBe Some(taxpayerName)
    }

    "create and persist the new message format with TaxPayerName(line1, line2, line2) to DB" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithName(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = Some(taxpayer3Names)
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.CREATED
      verify(mockRepo).insertIfUnique(messageCaptor.capture())

      private val storedMessage = messageCaptor.getValue
      storedMessage.alertDetails.recipientName mustBe Some(taxpayer3Names)
    }

    "create and persist the new message format with TaxPayerName(line1, line2 = '', line3 = '  ') to DB" in new TestCase {
      val gmcTaxPayerName2Empty = TaxpayerName(line1 = Some("Line1"), line2 = Some("  "), line3 = Some(""))
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithName(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = Some(gmcTaxPayerName2Empty)
      )

      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))
      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.CREATED
      verify(mockRepo).insertIfUnique(messageCaptor.capture())

      private val storedMessage = messageCaptor.getValue
      val gmcTaxPayerName2EmptyResult = TaxpayerName(line1 = Some("Line1"), line2 = Some(""), line3 = Some(""))
      storedMessage.alertDetails.recipientName mustBe Some(gmcTaxPayerName2EmptyResult)
    }

    "create and persist the new message format with TaxPayerName(line1, line2 is absent, line3 is absent) to DB" in new TestCase {
      val gmcTaxPayerName2Absent = TaxpayerName(line1 = Some("Line1"), line2 = None, line3 = None)
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithName(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = Some(gmcTaxPayerName2Absent)
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      private val messageCaptor: ArgumentCaptor[Message] = ArgumentCaptor.forClass(classOf[Message])

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.CREATED
      verify(mockRepo).insertIfUnique(messageCaptor.capture())

      private val storedMessage = messageCaptor.getValue
      val gmcTaxPayerName2EmptyResult = TaxpayerName(line1 = Some("Line1"), line2 = None, line3 = None)
      storedMessage.alertDetails.recipientName mustBe Some(gmcTaxPayerName2EmptyResult)
    }

    "not throw error message with TaxPayerName(line1 is absent, line2 is absent, line3 is absent)" in new TestCase {
      val gmcTaxPayerName2Absent = TaxpayerName(line1 = None, line2 = None, line3 = None)
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithName(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        recipientName = Some(gmcTaxPayerName2Absent)
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))
      when(mockMessageConnector.postMessage(any[JsValue])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(CREATED, "")))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.CREATED
    }

  }

  "message - v3 API Ivalid Date Check" must {

    "throw error message with invalid issueDate" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        issueDate = "23052018"
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"Invalid date format provided"}"""
      )
    }

    "throw error message with invalid validFrom" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmptyFields(
        recipient = TaxEntity(Regime.sa, utr),
        messageRef = externalRef,
        form = "SA316",
        batchId = Some("1234"),
        validFrom = "23052018"
      )
      when(mockRepo.insertIfUnique(any[Message])).thenReturn(Future.successful(true))
      when(mockAuditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(AuditResult.Success))

      val fakeRequest =
        FakeRequest(Helpers.POST, routes.MessagesController.createMessage().url, FakeHeaders(), Json.toJson(newMessage))

      private val result = messageController.createMessage()(fakeRequest)

      status(result) mustBe Status.BAD_REQUEST
      contentAsString(result) must include(
        """{"failureId":"INVALID_PAYLOAD","reason":"Invalid date format provided"}"""
      )
    }
  }

  "message - v3 API Email and TaxId Check" must {
    "throw error message when invalid taxid given" in new TestCase {
      private val externalRef = ExternalRef(id = "abcd1234", source = "mdtp")
      private val newMessage = messageJsonForV3WithEmailAndTaxIDCheck(
        recipient = TaxEntity(Regime.sa, Vrn("123455789")),
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

  trait TestCase {
    reset(mockRepo)
    reset(mockAuthConnector)
    reset(mockAuditConnector)
    reset(mockMessageConnector)
    reset(mockTaxpayerNameConnector)

    implicit val headerCarrier: HeaderCarrier = HeaderCarrier()
    val utr: SaUtr = GenerateRandom.utr()
    val nino: Nino = GenerateRandom.nino()
    val ctUtr: CtUtr = CtUtr("xxxxx")
    val taxIds: Set[TaxIdWithName] = Set(utr, nino)

    val v3TaxpayerName = TaxpayerName(
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

    def messageJsonForV3(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "some-source"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String,
      subject: String = "Test subject",
      content: String = "PGgyPlRlc3QgY29udGVudDwvaDI+", // => "<h2>Test content</h2>"
      form: String = "SA251",
      validFrom: LocalDate = LocalDate.now,
      statutory: Boolean = false,
      paperSent: Boolean = false,
      batchId: Option[String] = None,
      sourceData: Option[String] = None,
      emailAlertEventUrl: Option[String] = Some("/someUrl")
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
           |   ${sourceData.map(s => s""" "sourceData":"$s",""").getOrElse("")}
           |   "subject":"$subject",
           |   "content":"$content",
           |   ${emailAlertEventUrl.map(url => s""" "emailAlertEventUrl":"$url", """).getOrElse(",")}
           |   "details":{
           |      "formId":"$form",
           |      "statutory":$statutory,
           |      "paperSent":$paperSent
           |      ${batchId.map(id => s""", "batchId":"$id" """).getOrElse("")}
           |   }
           |}
    """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }

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

    def messageJsonForV3WithAlertQueue(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "some-source"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String = "response-from-customer-advisor",
      subject: String = "Test subject",
      content: String = "PGgyPlRlc3QgY29udGVudDwvaDI+", // => "<h2>Test content</h2>"
      form: String = "SA251",
      validFrom: LocalDate = LocalDate.now,
      statutory: Boolean = false,
      paperSent: Boolean = false,
      batchId: Option[String] = None,
      alertQueue: Option[String] = Some("DEFAULT")
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
           |   "alertQueue": "${alertQueue.get}",
           |   "details":{
           |      "formId":"$form",
           |      "statutory":$statutory,
           |      "paperSent":$paperSent
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

    def messageJsonForV3WithEmptyFields(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "customer-advisors-frontend"),
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
      emailAddress: String = "test@test.com",
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
           |      "name":${Json.toJson(recipientName).toString()},
           |      "email":"$emailAddress"
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

    def largeBase64EncodedContent(): String = {
      val contentBuilder = new StringBuilder
      for (_ <- 1 to 1300) contentBuilder ++= Random.alphanumeric.take(100).mkString
      Base64.encodeBase64String(contentBuilder.toString().getBytes("UTF-8"))
    }

    def messageJsonForV3GMC(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "123412342314", source = "gmc"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String = "mailout-batch",
      subject: String = "Reminder to file a Self Assessment return",
      content: String =
        "PGgxPlRlc3QgY29udGVudDwvaDE+IDxzY3JpcHQ+d2luZG93LmFsZXJ0KCJoZWxsbyIpPC9zY3JpcHQ+IDxwPmJvZHk8L3A+", // => "<h1>Test content</h1> <script>window.alert("hello")</script> <p>body</p>"
      form: String = "SA251",
      validFrom: LocalDate = LocalDate.now,
      statutory: Boolean = true,
      paperSent: Boolean = false,
      batchId: Option[String] = Some("1234567"),
      sourceData: String // => "{ "name" : "value" }"
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
           |   "subject":"$subject",
           |   "content":"$content",
           |   "validFrom": "${formatDate(validFrom)}",
           |   "details":{
           |      "formId":"$form",
           |      "statutory":$statutory,
           |      "paperSent":$paperSent,
           |      "sourceData": "$sourceData"
           |      ${batchId.map(id => s""", "batchId":"$id" """).getOrElse("")}
           |   }
           |}
    """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }

    def messageJsonForV3WithName(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "customer-advisors-frontend"),
      recipientName: Option[TaxpayerName] = Some(taxpayerName),
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
      emailAddress: String = "test@test.com",
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
           |      "name":${Json.toJson(recipientName).toString()},
           |      "email":"$emailAddress"
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

    def messageJsonForV3ForFhdds(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "customer-advisors-frontend"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String = "fhddsAlertMessage",
      subject: String = "Test subject",
      content: String = "PGgyPlRlc3QgY29udGVudDwvaDI+", // => "<h2>Test content</h2>"
      validFrom: String = LocalDate.now.toString,
      emailAddress: String = "test@test.com",
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
           |      "name":${Json.toJson(recipientName).toString()},
           |      "email":"$emailAddress"
           |      ${recipient.email.map(e => s""", "email":"$e"""").getOrElse("")}
           |   },
           |   "messageType":"$messageType",
           |   "validFrom": "$validFrom",
           |   "subject":"$subject",
           |   "content":"$content",
           |   "alertQueue":"$alertQueue"
           |}
    """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }

    val messageWithInvalidAlertDetails =
      """{
        |    "externalRef":{"id":"P1234567-123","source":"gmc"},
        |    "recipient":{"taxIdentifier":{"name":"nino","value":"SP902003A"},"name":{"line1":"Mr","line2":"Donald","line3":"Duck"},"email":"donald.duck@disney.com"},
        |    "regime":"Child-Benefit",
        |    "alertDetails":{"data":[{"name":"language","value":"english"}]},
        |    "messageType":"mailout-batch",
        |    "channel":"EMAIL",
        |    "subject":"Test Email for AA123456A",
        |    "validFrom":"2021-03-16",
        |    "alertQueue":"BACKGROUND",
        |    "content":"SGVsbG8gd29ybGQhIQ=="
        |}""".stripMargin

    def messageJsonForV3InvalidGmc(
      recipient: TaxEntity,
      messageRef: ExternalRef = ExternalRef(id = "12341234", source = "customer-advisors-frontend"),
      recipientName: Option[TaxpayerName] = Some(v3TaxpayerName),
      messageType: String = "mailout-batch",
      subject: String = "Test subject",
      content: String = "PGgyPlRlc3QgY29udGVudDwvaDI+", // => "<h2>Test content</h2>"
      validFrom: String = LocalDate.now.toString,
      emailAddress: String = "test@test.com",
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
           |      "name":${Json.toJson(recipientName).toString()},
           |      "email":"$emailAddress"
           |      ${recipient.email.map(e => s""", "email":"$e"""").getOrElse("")}
           |   },
           |   "messageType":"$messageType",
           |   "validFrom": "$validFrom",
           |   "subject":"$subject",
           |   "content":"$content",
           |   "alertQueue":"$alertQueue"
           |}
    """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }
  }
}
