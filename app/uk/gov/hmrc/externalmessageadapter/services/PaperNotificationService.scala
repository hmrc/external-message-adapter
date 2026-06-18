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

import play.api.http.Status
import play.api.{ Configuration, Logging }
import play.api.libs.json.{ JsValue, Json }
import uk.gov.hmrc.externalmessageadapter.connectors.EISConnector
import uk.gov.hmrc.externalmessageadapter.model.{ GmcPrintRequest, GmcPrintResponse }
import uk.gov.hmrc.common.message.model.Message
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ Audit, DataEvent, EventTypes }

import java.time.{ Instant, ZoneOffset }
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.{ Inject, Named, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class PaperNotificationService @Inject() (
  @Named("app-name") val appName: String,
  eisConnector: EISConnector,
  auditConnector: AuditConnector,
  messageRepository: MongoMessageRepository,
  configuration: Configuration
) extends Logging {

  lazy val audit: Audit = new Audit(appName, auditConnector)
  private val ACKNOWLEDGEMENT_REFERENCE_MAX_LENGTH_MINUS_ONE = 31

  def sendGmcPaperNotification(message: Message, emailAddress: String, properties: Option[JsValue] = None)(implicit
    hc: HeaderCarrier,
    ec: ExecutionContext
  ): Future[Option[GmcPrintResponse]] = {

    def detailsMap(req: GmcPrintRequest, id: String): Map[String, String] =
      Map("correlationId" -> id, "body" -> Json.toJson(req).toString())

    GmcPrintRequest.fromMessage("EMAIL_BOUNCE", message, emailAddress, properties) match {
      case Some(request) if !handleBounce =>
        Future {
          auditMessage(
            message,
            transactionName = "EventHub Paper Letter",
            additionalDetails = Map("body" -> Json.toJson(request).toString())
          )
          None
        }
      case Some(request) =>
        val correlationId =
          UUID.randomUUID().toString.replace("-", "").substring(0, ACKNOWLEDGEMENT_REFERENCE_MAX_LENGTH_MINUS_ONE)
        (for {
          created <- eisConnector.post(request, correlationId)
          _ = logger warn s"Eventhub Processor $created"
          _ <- if (created.isEmpty) messageRepository.removeById(message.id) else Future.successful(false)
          _ = auditMessage(message, additionalDetails = detailsMap(request, correlationId) ++ responseDetails(created))
        } yield created)
          .recoverWith { case e =>
            logger.error(s"Unable to send print request GMC ${e.getMessage}")
            auditMessage(message, eventType = EventTypes.Failed, additionalDetails = detailsMap(request, correlationId))
            Future.failed(e)
          }
      case _ =>
        logger.warn(
          s"No GmcPrintRequest to send for message ${message.externalRef.map(_.id).getOrElse(message.id.toString)}"
        )
        Future.successful(None)
    }
  }

  private def responseDetails(response: Option[GmcPrintResponse]): Map[String, String] =
    response
      .map { r =>
        Map("response.status" -> r.status.toString, "response.error" -> r.message)
      }
      .getOrElse(Map("response.status" -> Status.OK.toString))

  private def sendDataEvent(
    transactionName: String,
    path: String = "N/A",
    tags: Map[String, String],
    detail: Map[String, String],
    eventType: String
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit =
    audit.sendDataEvent(
      DataEvent(
        appName,
        eventType,
        tags = hc.toAuditTags(transactionName, path) ++ tags,
        detail = hc.toAuditDetails(detail.toSeq*)
      )
    )

  private def formatDateTime(dateTime: Instant): String =
    DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(dateTime)

  private def auditMessage(
    message: Message,
    transactionName: String = "Hardcopy Reminder Letter Requested",
    eventType: String = EventTypes.Succeeded,
    additionalDetails: Map[String, String]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit = {
    val tags = Map(
      "reason" -> "Bounced Message Detected"
    )

    val withoutEmailAlert = Map(
      "messageId"                       -> message.id.toString,
      message.recipient.identifier.name -> message.recipient.identifier.value,
      "validFrom"                       -> message.validFrom.toString
    ) ++ message.body.map(_.toMap).getOrElse(Map.empty)

    val detail: Map[String, String] = (message.alerts match {
      case Some(alert) if alert.emailAddress.nonEmpty =>
        withoutEmailAlert ++ Map(
          "emailAddress" -> alert.emailAddress.get,
          "alertTime"    -> formatDateTime(alert.alertTime)
        )

      case _ => withoutEmailAlert
    }) ++ additionalDetails

    sendDataEvent(transactionName, tags = tags, detail = detail, eventType = eventType)
  }

  def auditOnly(message: Message)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = {
    auditMessage(message, "Hardcopy Reminder Letter Suppressed", additionalDetails = Map.empty)
    Future.successful(())
  }

  lazy val handleBounce: Boolean =
    configuration.getOptional[Boolean]("handle.bounce.eventhub").getOrElse(false)

}
