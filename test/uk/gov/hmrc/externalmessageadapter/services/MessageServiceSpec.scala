/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.services

import java.time.Instant
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mongodb.scala.bson.ObjectId
import org.scalatest.OptionValues
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.WsScalaTestClient
import play.api.http.Status.{ INTERNAL_SERVER_ERROR, NO_CONTENT }
import play.api.i18n.{ Messages, MessagesApi }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.{ Injector, bind }
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import play.api.test.Helpers.{ status, * }
import uk.gov.hmrc.common.message.model.*
import uk.gov.hmrc.domain.SaUtr
import uk.gov.hmrc.externalmessageadapter.GenerateRandom
import uk.gov.hmrc.externalmessageadapter.model.*
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.http.{ HeaderCarrier, UpstreamErrorResponse }
import uk.gov.hmrc.http.UpstreamErrorResponse.Upstream4xxResponse
import uk.gov.hmrc.externalmessageadapter.util.TestData.TEST_SOURCE_DATA

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.*
import scala.concurrent.{ ExecutionContext, Future }

class MessageServiceSpec
    extends AnyFreeSpec with Matchers with OptionValues with WsScalaTestClient with MockitoSugar with ScalaFutures
    with IntegrationPatience {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  val responseAsJson = (reason: String, failureId: String) =>
    Json.parse(s"""{"failureId":"$failureId","reason":"$reason"}""")

  val mockMessageRepository: MongoMessageRepository =
    mock[MongoMessageRepository]

  val mockPaperNotificationService: PaperNotificationService =
    mock[PaperNotificationService]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MongoMessageRepository].toInstance(mockMessageRepository))
    .overrides(bind[PaperNotificationService].toInstance(mockPaperNotificationService))
    .configure(
      "gmc.denylist"    -> List("SA999", "SA888", "ATSV2"),
      "metrics.enabled" -> "false"
    )
    .injector()

  trait TestCase {

    reset(mockMessageRepository)

    val messageId: ObjectId = new ObjectId

    val testTime: Instant = Instant.now()

    val uuid = UUID.randomUUID()

    val messageService: MessageService = injector.instanceOf[MessageService]
    val messagesApi: MessagesApi = injector.instanceOf[MessagesApi]
    implicit lazy val fakeRequest: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
    implicit lazy val messages: Messages = messagesApi.preferred(fakeRequest)

    val authorisedUtr: SaUtr = GenerateRandom.utr()

    def getMessageForAuthorisedUtr(
      mongoId: ObjectId,
      content: String,
      testTime: Option[Instant],
      uuid: Option[UUID] = Some(UUID.randomUUID()),
      formId: String = "SA300"
    ): Message =
      MessageFixtures.testMessageWithContent(
        id = mongoId,
        recipientId = authorisedUtr,
        uuid = uuid.get,
        testTime = testTime,
        content = content,
        form = formId
      )

    def createProcessEventMessage(
      readTime: Option[Instant] = None,
      statutory: Boolean = true,
      formId: String = "SA300",
      source: Option[String] = None
    ): Message =
      MessageFixtures
        .testMessageWithoutContent(form = formId)
        .copy(
          readTime = readTime,
          statutory = statutory,
          externalRef = source.map(ExternalRef(new ObjectId().toString, _))
        )

  }

  "processBounceEvent" - {
    "return NoContent if there is no corresponding message in the database" in new TestCase {
      when(mockMessageRepository.findByExternalRefId(any[String]))
        .thenReturn(Future.successful(None))

      val externalRef = "123412342314"
      val emailAddress = "emailAddress"

      val result: Future[mvc.Result] = messageService.processBounceEvent(externalRef, emailAddress)

      status(result) mustBe NO_CONTENT

    }

    "return NoContent if formId of the message is in the gmc-denyList " in new TestCase {
      private val messageForAuthorisedUtr =
        getMessageForAuthorisedUtr(messageId, "Hello World!", Some(testTime), formId = "AtSV2")
      when(mockMessageRepository.findByExternalRefId(any[String]))
        .thenReturn(Future.successful(Some(messageForAuthorisedUtr)))
      when(mockPaperNotificationService.auditOnly(any[Message])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(()))

      val externalRef = "123412342314"
      val emailAddress = "emailAddress"

      val result =
        messageService.processBounceEvent(externalRef, emailAddress)

      status(result) mustBe NO_CONTENT
    }

    "return NoContent if there is a corresponding message in the database " +
      "and the call to EIS is successful" in new TestCase {
        private val messageForAuthorisedUtr =
          getMessageForAuthorisedUtr(messageId, "Hello World!", Some(testTime))
        when(mockMessageRepository.findByExternalRefId(any[String]))
          .thenReturn(Future.successful(Some(messageForAuthorisedUtr)))
        when(
          mockPaperNotificationService.sendGmcPaperNotification(any[Message], any[String], any[Option[JsValue]])(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
        )
          .thenReturn(Future.successful(None))

        val externalRef = "123412342314"
        val emailAddress = "emailAddress"

        val result =
          messageService.processBounceEvent(externalRef, emailAddress)

        status(result) mustBe NO_CONTENT
      }

    "return OK if there is BAD_REQUEST received from eis connector" in new TestCase {
      private val messageForAuthorisedUtr: Message =
        getMessageForAuthorisedUtr(messageId, TEST_SOURCE_DATA, Some(testTime), formId = "AtSV2")

      val updatedDetails: Option[Details] = messageForAuthorisedUtr.body.map(body => body.copy(form = None))
      val updatedMessage: Message = messageForAuthorisedUtr.copy(body = updatedDetails)

      when(mockMessageRepository.findByExternalRefId(any[String]))
        .thenReturn(Future.successful(Some(updatedMessage)))

      when(mockPaperNotificationService.sendGmcPaperNotification(any, any, any)(any, any))
        .thenReturn(Future.failed(UpstreamErrorResponse("Unknown eis error", BAD_REQUEST)))

      val externalRef = "123412342314"
      val emailAddress = "emailAddress"

      val result: Future[mvc.Result] = messageService.processBounceEvent(externalRef, emailAddress)

      status(result) must be(OK)
    }

    "return INTERNAL_SERVER_ERROR if there is an INTERNAL_SERVER_ERROR received from eis connector" in new TestCase {
      private val messageForAuthorisedUtr: Message =
        getMessageForAuthorisedUtr(messageId, TEST_SOURCE_DATA, Some(testTime), formId = "AtSV2")

      val updatedDetails: Option[Details] = messageForAuthorisedUtr.body.map(body => body.copy(form = None))
      val updatedMessage: Message = messageForAuthorisedUtr.copy(body = updatedDetails)

      when(mockMessageRepository.findByExternalRefId(any[String]))
        .thenReturn(Future.successful(Some(updatedMessage)))

      when(mockPaperNotificationService.sendGmcPaperNotification(any, any, any)(any, any))
        .thenReturn(
          Future.failed(UpstreamErrorResponse("Dependent systems are currently not responding.", INTERNAL_SERVER_ERROR))
        )

      val externalRef = "123412342314"
      val emailAddress = "emailAddress"

      val result: Future[mvc.Result] = messageService.processBounceEvent(externalRef, emailAddress)

      status(result) must be(INTERNAL_SERVER_ERROR)
    }

    List(
      (
        INTERNAL_SERVER_ERROR,
        INTERNAL_SERVER_ERROR,
        "IF is currently experiencing problems that require live service intervention.",
        "SERVER_ERROR"
      ),
      (OK, BAD_REQUEST, "Submission has not passed validation. Invalid payload.", "INVALID_PAYLOAD"),
      (OK, BAD_REQUEST, "The remote endpoint has indicated that the request is invalid.", "INVALID_REQUEST"),
      (OK, BAD_REQUEST, "Submission has not passed validation. Invalid header CorrelationId.", "INVALID_CORRELATIONID"),
      (OK, BAD_REQUEST, "Unknown eis error", "SERVER_ERROR"),
      (INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR, "Unknown eis error", "SERVER_ERROR"),
      (
        INTERNAL_SERVER_ERROR,
        SERVICE_UNAVAILABLE,
        "Dependent systems are currently not responding.",
        "SERVICE_UNAVAILABLE"
      )
    ).foreach { case (responseStatus, eisStatus, message, code) =>
      s"return $responseStatus if the call to EIS returns a ($eisStatus, $message)" in new TestCase {
        private val messageForAuthorisedUtr =
          getMessageForAuthorisedUtr(messageId, "Hello World!", Some(testTime))
        when(mockMessageRepository.findByExternalRefId(any[String]))
          .thenReturn(Future.successful(Some(messageForAuthorisedUtr)))
        when(
          mockPaperNotificationService.sendGmcPaperNotification(any[Message], any[String], any[Option[JsValue]])(
            any[HeaderCarrier],
            any[ExecutionContext]
          )
        )
          .thenReturn(
            Future.successful(
              Some(GmcPrintResponse(eisStatus, message))
            )
          )

        val externalRef = "123412342314"
        val emailAddress = "emailAddress"

        val result =
          messageService.processBounceEvent(externalRef, emailAddress)

        status(result) mustBe responseStatus
        private val expectedResponseBody = responseAsJson(message, code)

        contentAsJson(result) mustBe expectedResponseBody
      }

    }

    "return 500 if the call to EIS returns a 500" in new TestCase {
      private val messageForAuthorisedUtr =
        getMessageForAuthorisedUtr(messageId, "Hello World!", Some(testTime))
      when(mockMessageRepository.findByExternalRefId(any[String]))
        .thenReturn(Future.successful(Some(messageForAuthorisedUtr)))
      when(
        mockPaperNotificationService.sendGmcPaperNotification(any[Message], any[String], any[Option[JsValue]])(
          any[HeaderCarrier],
          any[ExecutionContext]
        )
      )
        .thenReturn(
          Future.successful(
            Some(
              GmcPrintResponse(
                INTERNAL_SERVER_ERROR,
                "IF is currently experiencing problems that require live service intervention."
              )
            )
          )
        )
      val externalRef = "123412342314"
      val emailAddress = "emailAddress"

      val result =
        messageService.processBounceEvent(externalRef, emailAddress)

      status(result) mustBe INTERNAL_SERVER_ERROR
      private val expectedResponseBody =
        responseAsJson(
          "IF is currently experiencing problems that require live service intervention.",
          "SERVER_ERROR"
        )
      contentAsJson(result) mustBe expectedResponseBody
    }
  }

  "processDeliveredEvent" - {
    "return no content" in new TestCase {
      when(mockMessageRepository.processDeliveredEvent(any[String], any[Instant]))
        .thenReturn(Future(()))
      val externalRefId = UUID.randomUUID().toString()
      val deliveredOn = java.time.LocalDateTime.now()
      val result =
        messageService.processDeliveredEvent(externalRefId, deliveredOn)
      status(result) mustBe NO_CONTENT
    }

    "return no content if the call to the repo fails" in new TestCase {
      when(mockMessageRepository.processDeliveredEvent(any[String], any[Instant]))
        .thenReturn(Future.failed(new Exception))
      val externalRefId = UUID.randomUUID().toString()
      val deliveredOn = java.time.LocalDateTime.now()
      val result =
        messageService.processDeliveredEvent(externalRefId, deliveredOn)
      status(result) mustBe NO_CONTENT
    }
  }
}
