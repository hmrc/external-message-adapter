/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.services

import java.time.{ Instant, ZoneOffset }
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.Configuration
import uk.gov.hmrc.common.message.model._
import uk.gov.hmrc.domain.SaUtr
import uk.gov.hmrc.externalmessageadapter.MetricOrchestratorStub
import uk.gov.hmrc.externalmessageadapter.connectors.EISConnector
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.DataEvent

import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext

class PaperNotificationServiceWithSuppressSpec
    extends PlaySpec with MockitoSugar with LoneElement with ScalaFutures with MetricOrchestratorStub
    with IntegrationPatience {

  val auditConnector = mock[AuditConnector]
  val eisConnector = mock[EISConnector]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[AuditConnector].toInstance(auditConnector))
    .overrides(bind[EISConnector].toInstance(eisConnector))
    .configure(
      "gmc.denylist"                -> List("SA999", "SA888"),
      "metrics.enabled"             -> "false",
      "quadiant.eis"                -> "true",
      "suppressEventhubBounceEvent" -> "true"
    )
    .injector()

  implicit val hc: HeaderCarrier = HeaderCarrier()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  "On receiving a GMC message, paper notification and suppressEventhubBounceEvent is true" must {
    val form = "SA316 2014"
    val suppressedAt = "2013-01-02"
    val alertTime = Instant.now

    "audit the message and do not send hard copy request to GMC" in new TestCase {
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
        contain("transactionName" -> "EventHub Paper Letter")
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

      verifyNoInteractions(eisConnector)
    }
  }

  trait TestCase {
    val dataEvents = new ArrayBuffer[DataEvent]()

    when(auditConnector.sendEvent(any[DataEvent])(any[HeaderCarrier], any[ExecutionContext]))
      .thenAnswer(new EISSendEventAnswer(dataEvents))

    lazy val service: PaperNotificationService =
      injector.instanceOf[PaperNotificationService]

    lazy val config = injector.instanceOf[Configuration]
  }

}
