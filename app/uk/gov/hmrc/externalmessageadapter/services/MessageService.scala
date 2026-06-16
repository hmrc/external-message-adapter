/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.externalmessageadapter.services

import play.api.Logger
import play.api.http.Status.*
import play.api.mvc.Result
import play.api.mvc.Results.NoContent
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.common.message.failuremodule.FailureResponseService.errorResponseResult
import uk.gov.hmrc.common.message.model.{ Details, Message }
import uk.gov.hmrc.http.UpstreamErrorResponse.Upstream4xxResponse
import uk.gov.hmrc.http.UpstreamErrorResponse.Upstream5xxResponse
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{ LocalDateTime, ZoneOffset }
import javax.inject.{ Inject, Named, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class MessageService @Inject() (
  messageRepository: MongoMessageRepository,
  paperNotificationService: PaperNotificationService,
  @Named("denyListedFormIds") denyListedFormIds: Seq[String]
) {

  val logger: Logger = Logger(this.getClass)
  def processBounceEvent(
    messageId: String,
    emailAddress: String
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] =
    messageRepository.findByExternalRefId(messageId).flatMap {
      case None =>
        logger.debug(s"EventHub Processor: there is no message in collection for the is $messageId")
        Future.successful(NoContent)
      case Some(message) if isDenyListed(message.body) =>
        logger.debug(
          s"EventHub Processor: the formId used for the message $messageId is in the gmc denied list $denyListedFormIds"
        )
        paperNotificationService.auditOnly(message).map(_ => NoContent)
      case Some(message) =>
        val properties = message.body.flatMap(_.properties)
        paperNotificationService
          .sendGmcPaperNotification(message, emailAddress, properties)
          .map {
            case None =>
              logger debug "EventHub Processor: Sending paper notification successful"
              NoContent
            case Some(gmcResponse) =>
              logger debug s"EventHub Processor: Failure Sending paper notification :$gmcResponse"
              statusHelper(gmcResponse.status, gmcResponse.message)
          }
          .recover {
            case Upstream4xxResponse(error) =>
              statusHelper(error.statusCode, error.message)
            case Upstream5xxResponse(error) =>
              statusHelper(error.statusCode, error.message)
          }
    }

  private def isDenyListed(details: Option[Details]): Boolean =
    details.flatMap(_.form) match {
      case Some(formId) => denyListedFormIds.map(_.toLowerCase).contains(formId.toLowerCase)
      case _            => false
    }

  def processDeliveredEvent(
    messageId: String,
    deliveredOn: LocalDateTime
  )(implicit ec: ExecutionContext): Future[Result] =
    messageRepository
      .processDeliveredEvent(messageId, deliveredOn.toInstant(ZoneOffset.UTC))
      .map(_ => NoContent) recover { case e: Exception =>
      logger.error("error processing delivered event", e)
      NoContent
    }

  private def statusHelper(statusCode: Int, message: String): Result =
    statusCode match {
      case BAD_REQUEST =>
        logger.info(s"EventHub Processor: Received a 400 from eis connector with the error message: $message")
        errorResponseResult(message, OK, showErrorID = true)
      case INTERNAL_SERVER_ERROR => errorResponseResult(message, INTERNAL_SERVER_ERROR, showErrorID = true)
      case SERVICE_UNAVAILABLE   => errorResponseResult(message, INTERNAL_SERVER_ERROR, showErrorID = true)
      case r                     => errorResponseResult(message, r, showErrorID = true)
    }
}
