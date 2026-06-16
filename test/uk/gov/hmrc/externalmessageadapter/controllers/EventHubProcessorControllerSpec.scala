/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mongodb.scala.bson.ObjectId
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.Results.NoContent
import play.api.test.Helpers._
import play.api.test.{ FakeHeaders, FakeRequest, Helpers }
import uk.gov.hmrc.externalmessageadapter.MetricOrchestratorStub
import uk.gov.hmrc.externalmessageadapter.services.MessageService
import uk.gov.hmrc.externalmessageadapter.validators.MessagesUtil
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator

import java.time.LocalDateTime
import scala.concurrent.{ ExecutionContext, Future }

class EventHubProcessorControllerSpec extends PlaySpec with ScalaFutures with MetricOrchestratorStub {

  val mockMessageService = mock[MessageService]
  val mockMessageUtils = mock[MessagesUtil]

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[MessageService].toInstance(mockMessageService))
    .overrides(bind[MessagesUtil].toInstance(mockMessageUtils))
    .configure(
      "metrics.enabled"           -> "false",
      "quadient.check.enrolments" -> "false"
    )
    .injector()

  val eventHubProcessorController: EventHubProcessorController = injector.instanceOf[EventHubProcessorController]

  val messageId: String = new ObjectId().toString

  "EventHubProcessorController" must {
    "successfully process valid event-hub events" in {

      when(mockMessageService.processBounceEvent(any[String], any[String])(any[HeaderCarrier], any[ExecutionContext]))
        .thenReturn(Future.successful(NoContent))

      val jsonBody = Json.parse(
        s"""
           |{
           |    "eventId": "34b707f2-bbca-11ec-b4b8-3e22fb168568",
           |    "timestamp" : "2021-04-07T09:46:29+00:00",
           |    "event" : {
           |        "event": "PermanentBounce",
           |        "emailAddress": "hmrc-customer@some-domain.org",
           |        "detected": "2021-04-07T09:46:29+00:00",
           |        "code": 605,
           |        "reason": "Not delivering to previously bounced address",
           |        "tags": {
           |            "enrolment": "HMRC-MTD-VAT~VRN~GB123456789",
           |            "source": "gmc",
           |            "messageId": "$messageId"
           |        }
           |    }
           |}
           |""".stripMargin
      )
      val request = FakeRequest(
        Helpers.POST,
        routes.EventHubProcessorController.processEventHubEvents().url,
        FakeHeaders(),
        jsonBody
      )
      val res = eventHubProcessorController.processEventHubEvents()(request)
      status(res) mustEqual NO_CONTENT

    }

    "successfully process valid event-hub delivered event" in {
      when(mockMessageService.processDeliveredEvent(any[String], any[LocalDateTime])(any[ExecutionContext]))
        .thenReturn(Future.successful(NoContent))

      val jsonBody = Json.parse(
        s"""
           |{
           |    "eventId" : "1569eefc-c546-11ec-9527-3e22fb168568",
           |    "timestamp" : "2021-04-07T09:46:29+00:00",
           |    "event" : {
           |        "event": "Delivered",
           |        "emailAddress": "hmrc-customer@some-domain.org",
           |        "detected": "2021-04-07T09:46:29+00:00",
           |        "code": 605,
           |        "reason": "Not delivering to previously bounced address",
           |        "tags": {
           |            "enrolment": "HMRC-MTD-VAT~VRN~GB123456789",
           |            "source": "gmc",
           |            "messageId": "$messageId"
           |        }
           |    }
           |}
           |""".stripMargin
      )
      val request = FakeRequest(
        Helpers.POST,
        routes.EventHubProcessorController.processEventHubEvents().url,
        FakeHeaders(),
        jsonBody
      )
      val res = eventHubProcessorController.processEventHubEvents()(request)
      verify(mockMessageService).processDeliveredEvent(any[String], any[LocalDateTime])(any[ExecutionContext])
      status(res) mustEqual NO_CONTENT
    }

    "successfully process valid event-hub events for non-bounce events" in {

      val jsonBody = Json.parse(
        s"""
           |{
           |    "eventId": "34b707f2-bbca-11ec-b4b8-3e22fb168568",
           |    "timestamp" : "2021-04-07T09:46:29+00:00",
           |    "event" : {
           |        "event": "failed",
           |        "emailAddress": "hmrc-customer@some-domain.org",
           |        "detected": "2021-04-07T09:46:29+00:00",
           |        "code": 605,
           |        "reason": "Not delivering to previously bounced address",
           |        "tags": {
           |            "enrolment": "HMRC-MTD-VAT~VRN~GB123456789",
           |            "source": "gmc",
           |            "messageid": "$messageId"
           |        }
           |    }
           |}
           |""".stripMargin
      )
      val request = FakeRequest(
        Helpers.POST,
        routes.EventHubProcessorController.processEventHubEvents().url,
        FakeHeaders(),
        jsonBody
      )
      val res = eventHubProcessorController.processEventHubEvents()(request)
      status(res) mustEqual NO_CONTENT

    }

    "return 400 on process invalid event-hub events" in {

      val missingTimestampsBody = Json.parse(
        s"""
           |{
           |    "event" : {
           |        "event": "PermanentBounce",
           |        "emailAddress": "hmrc-customer@some-domain.org",
           |        "detected": "2021-04-07T09:46:29+00:00",
           |        "code": 605,
           |        "reason": "Not delivering to previously bounced address",
           |        "tags": {
           |            "enrolment": "HMRC-MTD-VAT~VRN~GB123456789",
           |            "source": "gmc",
           |            "messageId": "$messageId"
           |        }
           |    }
           |}
           |""".stripMargin
      )
      val request = FakeRequest(
        Helpers.POST,
        routes.EventHubProcessorController.processEventHubEvents().url,
        FakeHeaders(),
        missingTimestampsBody
      )
      val res = eventHubProcessorController.processEventHubEvents()(request)
      status(res) mustEqual BAD_REQUEST

    }

    "swallow (204) process event-hub events when missing the messageId" in {

      val missingSubjectJsonBody = Json.parse(
        """
          |{
          |    "eventId": "34b707f2-bbca-11ec-b4b8-3e22fb168568",
          |    "timestamp" : "2021-04-07T09:46:29+00:00",
          |    "event" : {
          |        "event": "PermanentBounce",
          |        "emailAddress": "hmrc-customer@some-domain.org",
          |        "detected": "2021-04-07T09:46:29+00:00",
          |        "code": 605,
          |        "reason": "Not delivering to previously bounced address",
          |        "tags": {
          |            "enrolment": "HMRC-MTD-VAT~VRN~GB123456789",
          |            "source": "gmc"
          |        }
          |    }
          |}
          |""".stripMargin
      )
      val request = FakeRequest(
        Helpers.POST,
        routes.EventHubProcessorController.processEventHubEvents().url,
        FakeHeaders(),
        missingSubjectJsonBody
      )
      val res = eventHubProcessorController.processEventHubEvents()(request)
      status(res) mustEqual NO_CONTENT

    }

    "swallow (204) on process event-hub events when invalid the messageId" in {

      val missingSubjectJsonBody = Json.parse(
        """
          |{
          |    "eventId": "34b707f2-bbca-11ec-b4b8-3e22fb168568",
          |    "timestamp" : "2021-04-07T09:46:29+00:00",
          |    "event" : {
          |        "event": "PermanentBounce",
          |        "emailAddress": "hmrc-customer@some-domain.org",
          |        "detected": "2021-04-07T09:46:29+00:00",
          |        "code": 605,
          |        "reason": "Not delivering to previously bounced address",
          |        "tags": {
          |            "enrolment": "HMRC-MTD-VAT~VRN~GB123456789",
          |            "source": "gmc",
          |            "messageId": "helloworld"
          |        }
          |    }
          |}
          |""".stripMargin
      )
      val request = FakeRequest(
        Helpers.POST,
        routes.EventHubProcessorController.processEventHubEvents().url,
        FakeHeaders(),
        missingSubjectJsonBody
      )
      val res = eventHubProcessorController.processEventHubEvents()(request)
      status(res) mustEqual NO_CONTENT

    }

  }

}
