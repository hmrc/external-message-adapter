/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.services

import java.time.{ Instant, ZoneOffset }
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.Configuration
import uk.gov.hmrc.domain.{ HmrcMtdItsa, Nino, SaUtr }
import uk.gov.hmrc.externalmessageadapter.MetricOrchestratorStub
import uk.gov.hmrc.externalmessageadapter.connectors.EISConnector
import uk.gov.hmrc.externalmessageadapter.model._
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.common.message.model._
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator
import uk.gov.hmrc.play.audit.http.connector.{ AuditConnector, AuditResult }
import uk.gov.hmrc.play.audit.model.DataEvent

import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class PaperNotificationServiceSpec
    extends PlaySpec with MockitoSugar with LoneElement with ScalaFutures with MetricOrchestratorStub
    with IntegrationPatience {

  val auditConnector = mock[AuditConnector]
  val eisConnector = mock[EISConnector]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[AuditConnector].toInstance(auditConnector))
    .overrides(bind[EISConnector].toInstance(eisConnector))
    .configure(
      "gmc.denylist"           -> List("SA999", "SA888"),
      "metrics.enabled"        -> "false",
      "quadiant.eis"           -> "true",
      "handle.bounce.eventhub" -> "true"
    )
    .injector()

  implicit val hc: HeaderCarrier = HeaderCarrier()

  "On receiving a GMC message, paper notification" must {
    val form = "SA316 2014"
    val suppressedAt = "2013-01-02"
    val alertTime = Instant.now

    "audit the message and send hard copy request to GMC" in new TestCase {
      when(eisConnector.post(any[GmcPrintRequest], any[String]))
        .thenReturn(Future.successful(None))

      val emailAddress = s"${UUID.randomUUID}@test.com"
      val alert = Some(EmailAlert(emailAddress = Some(emailAddress), alertTime, true, None))
      val externalRef = ExternalRef(s"${UUID.randomUUID}", "gmc")

      val message = MessageFixtures.testMessageWithoutContent(
        recipientId = SaUtr("123456789"),
        form = form,
        suppressedAt = suppressedAt,
        externalRef = Some(externalRef),
        alerts = alert,
        sourceData = Some("Some Hashed Content")
      )

      val actual = service.sendGmcPaperNotification(message, emailAddress).futureValue

      actual mustBe None

      val testEvent = dataEvents.head

      testEvent.tags must {
        contain("transactionName" -> "Hardcopy Reminder Letter Requested") and
          contain("reason" -> "Bounced Message Detected")
      }

      testEvent.auditType must be("TxSucceeded")
      testEvent.detail must {
        contain("sautr" -> "123456789") and
          contain("messageId" -> message.id.toString) and
          contain("formId" -> "SA316 2014") and
          contain("validFrom" -> message.validFrom.toString) and
          contain("suppressedAt" -> suppressedAt) and
          contain("emailAddress" -> emailAddress) and
          contain("alertTime" -> DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(alertTime)) and
          not contain ("deskProTicketSequenceNumber" -> "1")
      }
    }

    "audit the message and send hard copy request to GMC when the message has no body" in new TestCase {
      when(eisConnector.post(any[GmcPrintRequest], any[String]))
        .thenReturn(Future.successful(None))

      val emailAddress = s"${UUID.randomUUID}@test.com"
      val alert = Some(EmailAlert(emailAddress = Some(emailAddress), alertTime, true, None))
      val externalRef = ExternalRef(s"${UUID.randomUUID}", "gmc")

      val message = MessageFixtures
        .testMessageWithoutContent(
          recipientId = Nino("CE123456D"),
          form = form,
          suppressedAt = suppressedAt,
          externalRef = Some(externalRef),
          alerts = alert,
          sourceData = Some("Some Hashed Content")
        )
        .copy(body = None)

      val actual = service.sendGmcPaperNotification(message, emailAddress).futureValue

      actual mustBe None

      val testEvent = dataEvents.head

      testEvent.tags must {
        contain("transactionName" -> "Hardcopy Reminder Letter Requested") and
          contain("reason" -> "Bounced Message Detected")
      }

      testEvent.auditType must be("TxSucceeded")
      testEvent.detail must {
        contain("nino" -> "CE123456D") and
          contain("messageId" -> message.id.toString) and
          contain("validFrom" -> message.validFrom.toString) and
          contain("emailAddress" -> emailAddress) and
          contain("alertTime" -> DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(alertTime)) and
          not contain ("deskProTicketSequenceNumber" -> "1")
      }
    }

    "pass on formId, when it exist" in new TestCase {
      reset(eisConnector)
      when(eisConnector.post(any[GmcPrintRequest], any[String]))
        .thenReturn(Future.successful(None))

      val emailAddress = s"${UUID.randomUUID}@test.com"
      val alert = Some(EmailAlert(emailAddress = Some(emailAddress), alertTime, true, None))
      val externalRef = ExternalRef(s"${UUID.randomUUID}", "gmc")

      val message = MessageFixtures.testMessageWithoutContent(
        recipientId = SaUtr("123456789"),
        form = form,
        suppressedAt = suppressedAt,
        externalRef = Some(externalRef),
        alerts = alert,
        sourceData = Some("Some Hashed Content")
      )
      val properties = Some(Json.obj("randomKey" -> "randomValue"))

      val actual = service.sendGmcPaperNotification(message, emailAddress, properties).futureValue

      actual mustBe None

      private val gmcPrintRequestCaptor: ArgumentCaptor[GmcPrintRequest] =
        ArgumentCaptor.forClass(classOf[GmcPrintRequest])
      verify(eisConnector).post(gmcPrintRequestCaptor.capture(), any[String])
      gmcPrintRequestCaptor.getValue.formId must be(Some(form.filterNot(_.isWhitespace)))
    }

    "pass on properties, when they exist" in new TestCase {
      reset(eisConnector)
      when(eisConnector.post(any[GmcPrintRequest], any[String]))
        .thenReturn(Future.successful(None))

      val emailAddress = s"${UUID.randomUUID}@test.com"
      val alert = Some(EmailAlert(emailAddress = Some(emailAddress), alertTime, true, None))
      val externalRef = ExternalRef(s"${UUID.randomUUID}", "gmc")

      val message = MessageFixtures.testMessageWithoutContent(
        recipientId = SaUtr("123456789"),
        form = form,
        suppressedAt = suppressedAt,
        externalRef = Some(externalRef),
        alerts = alert,
        sourceData = Some("Some Hashed Content")
      )
      val properties = Some(Json.obj("randomKey" -> "randomValue"))

      val actual = service.sendGmcPaperNotification(message, emailAddress, properties).futureValue

      actual mustBe None

      private val gmcPrintRequestCaptor: ArgumentCaptor[GmcPrintRequest] =
        ArgumentCaptor.forClass(classOf[GmcPrintRequest])
      verify(eisConnector).post(gmcPrintRequestCaptor.capture(), any[String])
      gmcPrintRequestCaptor.getValue.properties must be(properties)
    }

    "do nothing, when the message as no source data" in new TestCase {
      reset(eisConnector)
      when(eisConnector.post(any[GmcPrintRequest], any[String]))
        .thenReturn(Future.successful(None))

      val emailAddress = s"${UUID.randomUUID}@test.com"
      val alert = Some(EmailAlert(emailAddress = Some(emailAddress), alertTime, true, None))
      val externalRef = ExternalRef(s"${UUID.randomUUID}", "gmc")

      val message = MessageFixtures.testMessageWithoutContent(
        recipientId = SaUtr("123456789"),
        form = form,
        suppressedAt = suppressedAt,
        externalRef = Some(externalRef),
        alerts = alert,
        sourceData = None
      )
      val properties = Some(Json.obj("randomKey" -> "randomValue"))

      val actual = service.sendGmcPaperNotification(message, emailAddress, properties).futureValue

      actual mustBe None

      private val gmcPrintRequestCaptor = ArgumentCaptor.forClass(classOf[GmcPrintRequest])
      verify(eisConnector, never()).post(gmcPrintRequestCaptor.capture(), any[String])
    }

    "audit the message even if hard copy request to GMC fails" in new TestCase {
      val testException = new Exception("test")
      when(eisConnector.post(any[GmcPrintRequest], any[String]))
        .thenReturn(Future.failed(testException))

      val emailAddress = s"${UUID.randomUUID}@test.com"
      val alert = Some(EmailAlert(emailAddress = Some(emailAddress), alertTime, true, None))
      val externalRef = ExternalRef(s"${UUID.randomUUID}", "gmc")

      val message = MessageFixtures.testMessageWithoutContent(
        recipientId = HmrcMtdItsa("XCIT00000564721"),
        form = form,
        suppressedAt = suppressedAt,
        externalRef = Some(externalRef),
        alerts = alert,
        sourceData = Some("Some Hashed Content")
      )

      service.sendGmcPaperNotification(message, emailAddress).failed.futureValue mustBe testException

      val testEvent = dataEvents.head

      testEvent.tags must {
        contain("transactionName" -> "Hardcopy Reminder Letter Requested") and
          contain("reason" -> "Bounced Message Detected")
      }

      testEvent.auditType must be("TxFailed")
      testEvent.detail must {
        contain("HMRC-MTD-IT" -> "XCIT00000564721") and
          contain("messageId" -> message.id.toString) and
          contain("formId" -> "SA316 2014") and
          contain("validFrom" -> message.validFrom.toString) and
          contain("suppressedAt" -> suppressedAt) and
          contain("emailAddress" -> emailAddress) and
          contain("alertTime" -> DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(alertTime)) and
          not contain ("deskProTicketSequenceNumber" -> "1")
      }
    }
  }

  trait TestCase {
    val dataEvents = new ArrayBuffer[DataEvent]()

    when(auditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
      .thenAnswer(new EISSendEventAnswer(dataEvents))

    when(eisConnector.post(any[GmcPrintRequest], any[String]))
      .thenReturn(Future.successful(None))

    lazy val service: PaperNotificationService =
      injector.instanceOf[PaperNotificationService]

    lazy val config = injector.instanceOf[Configuration]
  }

}

class EISSendEventAnswer(dataEvents: ArrayBuffer[DataEvent]) extends Answer[Future[AuditResult]] {

  override def answer(invocation: InvocationOnMock): Future[AuditResult] = {
    val event = invocation.getArguments.head.asInstanceOf[DataEvent]
    dataEvents.append(event)
    Future.successful(AuditResult.Success)
  }

}
