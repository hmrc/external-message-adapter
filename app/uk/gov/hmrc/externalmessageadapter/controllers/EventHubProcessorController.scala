/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers

import play.api.Logging
import play.api.i18n._
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.externalmessageadapter.model.{ BounceEvent, DeliveredEvent, EventHubEvent, UnhandledEvent }
import uk.gov.hmrc.externalmessageadapter.services.MessageService
import uk.gov.hmrc.externalmessageadapter.validators.MessagesUtil
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class EventHubProcessorController @Inject() (
  cc: MessagesControllerComponents,
  messageService: MessageService,
  messagesUtil: MessagesUtil
)(implicit ec: ExecutionContext)
    extends BackendController(cc) with Logging with I18nSupport {

  def processEventHubEvents(): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      withJsonBody[EventHubEvent] { event =>
        val (eventType, messageId) = (event.event.getEventType, event.event.getMessageId)
        logger.warn(
          s"EventHub Processor: Processing event ${event.eventId} with message ID $messageId" +
            s"with status $eventType at ${event.timestamp}"
        )
        messagesUtil.auditMessageDeliveryStatus(event)
        (eventType, messageId) match {
          case (BounceEvent | DeliveredEvent, None) =>
            logger.debug("EventHub Processor: The incoming event payload is missing the 'messageId'")
            Future.successful(NoContent)
          case (BounceEvent, Some(id)) =>
            logger debug s"EventHub Processor: handle $eventType"
            messageService.processBounceEvent(id, event.event.emailAddress)
          case (DeliveredEvent, Some(id)) =>
            logger debug s"EventHub Processor: handle $eventType"
            messageService.processDeliveredEvent(id, event.event.detected)
          case (UnhandledEvent, _) =>
            logger debug s"EventHub Processor: Unhandled event $eventType"
            Future.successful(NoContent)
        }
      }
    }

}
