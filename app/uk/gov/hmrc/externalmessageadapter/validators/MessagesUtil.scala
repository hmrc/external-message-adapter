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

package uk.gov.hmrc.externalmessageadapter.validators

import java.time.format.DateTimeFormatter
import java.time.LocalDate
import org.jsoup.Jsoup
import org.jsoup.nodes.Document.OutputSettings
import org.jsoup.safety.Safelist
import play.api.http.Status._
import play.api.i18n.Messages
import play.api.libs.json.{ JsObject, JsValue, Json }
import play.api.mvc.Results._
import play.api.mvc.{ Request, Result }
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.domain.SaUtr
import uk.gov.hmrc.externalmessageadapter.connectors._
import uk.gov.hmrc.common.message.model._
import uk.gov.hmrc.externalmessageadapter.model._
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.externalmessageadapter.validators.MessagesUtil.isGmc
import uk.gov.hmrc.http.UpstreamErrorResponse.Upstream4xxResponse
import uk.gov.hmrc.http.UpstreamErrorResponse.Upstream5xxResponse
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.{ AuditConnector, AuditResult }
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }
import uk.gov.hmrc.common.message.failuremodule.FailureResponseService.errorResponseResult
import uk.gov.hmrc.play.audit.AuditExtensions.auditHeaderCarrier

import javax.inject.{ Inject, Named }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object MessagesUtil {

  private val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy")

  def isGmc(message: Message): Boolean = message.externalRef match {
    case Some(ExternalRef(_, src)) if src.toLowerCase == "gmc" => true
    case _                                                     => false
  }

  def extractMessageDate(message: Message): String =
    message.body.flatMap(_.issueDate) match {
      case Some(issueDate) => formatter(issueDate)
      case None            => formatter(message.validFrom)
    }

  def localizedExtractMessageDate(message: Message)(implicit messages: Messages): String =
    message.body.flatMap(_.issueDate) match {
      case Some(issueDate) => localizedFormatter(issueDate)
      case None            => localizedFormatter(message.validFrom)
    }

  def formatter(date: LocalDate): String = date.format(dateFormatter)

  private def localizedFormatter(date: LocalDate)(implicit messages: Messages): String = {
    val formatter =
      if (messages.lang.language == "cy") {
        DateTimeFormatter.ofPattern(s"d '${messages(s"month.${date.getMonthValue}")}' yyyy")
      } else {
        dateFormatter
      }
    date.format(formatter)
  }

}

class MessagesUtil @Inject() (
  @Named("app-name") appName: String,
  messageRepository: MongoMessageRepository,
  audit: AuditConnector,
  taxpayerNameConnector: TaxpayerNameConnector,
  messageConnector: MessageConnector,
  enrolmentProxyConnector: EnrolmentProxyConnector,
  preferencesConnector: PreferencesConnector,
  configuration: Configuration,
  @Named("audit-event-max-size") auditEventMaxSize: Int
)(implicit ec: ExecutionContext) {

  val logger: Logger = Logger(this.getClass)
  private val NotificationType = "notificationType"
  val ShowErrorId = true

  def checkEnrolmentsAndProcessMessage(
    message: Message
  )(implicit request: Request[JsValue], hc: HeaderCarrier): Future[Result] = {
    val TAXPAYER_NOTFOUND = errorResponseResult(
      "The backend has rejected the message due to not being able to find the tax payer",
      NOT_FOUND,
      ShowErrorId
    )
    val enrolments = TaxEntity.getEnrolments(message.recipient)
    enrolmentProxyConnector.enrolments(enrolments).flatMap {
      case Right((enrolment, Some(p))) if p.principalUserIds.nonEmpty =>
        logger.warn(s"Enrolments(${p.principalUserIds}) found for $enrolment. Check for active users ")
        checkActiveUsers(p, message, enrolment)
      case _ =>
        logger.warn(s"No principalUserIds found for ${enrolments.main}")
        preferencesConnector.markPreferencesForDeEnrolment(message.recipient).flatMap { _ =>
          Future.successful(TAXPAYER_NOTFOUND)
        }
    }
  }

  def checkActiveUsers(p: Users, message: Message, enrolment: String)(implicit
    request: Request[JsValue],
    hc: HeaderCarrier
  ): Future[Result] = {
    val TAXPAYER_NOTFOUND = errorResponseResult(
      "The backend has rejected the message due to not being able to find the tax payer",
      NOT_FOUND,
      ShowErrorId
    )
    val validatePreferences =
      !Seq(
        "HMRC-MTD-VAT",
        "vrn",
        "HMRC-IOSS-ORG",
        "HMRC-IOSS-INT",
        "HMRC-IOSS-NETP",
        "HMRC-AD-ORG",
        "HMRC-OSS-ORG",
        "HMRC-PL"
      )
        .contains(
          message.recipient.identifier.name
        )

    enrolmentProxyConnector.hasActiveUsers(p.principalUserIds, enrolment).flatMap { hasActiveUser =>
      (hasActiveUser, validatePreferences) match {
        case (true, true) =>
          logger.warn(s"$enrolment has active users, check for the tax payer consent.")
          checkTaxpayerAndProcessMessage(message)
        case (true, false) =>
          logger.warn(s"$enrolment has active users, processing the message.")
          processMessage(message)
        case _ => Future.successful(TAXPAYER_NOTFOUND)
      }

    }
  }

  def checkTaxpayerAndProcessMessage(
    message: Message
  )(implicit request: Request[JsValue], hc: HeaderCarrier): Future[Result] = {
    val TAXPAYER_NOTFOUND = errorResponseResult(
      "The backend has rejected the message due to not being able to verify the email address.",
      NOT_FOUND,
      ShowErrorId
    )
    preferencesConnector
      .verifiedEmailAddress(message.recipient)
      .flatMap {
        _.value match {
          case Left(failure) if failure.getMessage.startsWith("email: not verified") =>
            Future.successful(errorResponseResult(failure.getMessage, BAD_REQUEST, ShowErrorId))
          case Left(failure) =>
            logger.warn(s"CreateMessage: $TAXPAYER_NOTFOUND - $failure")
            Future.successful(TAXPAYER_NOTFOUND)
          case _ =>
            if (checkEnrolmentsEnabled) {
              preferencesConnector.unsetMarkForDeEnrolment(message.recipient).flatMap { _ =>
                processMessage(message)
              }
            } else {
              processMessage(message)
            }
        }
      }
      .recover { case e =>
        logger.warn(s"MessagesUtil: ${e.getMessage}")
        buildBadRequest(e.getMessage)
      }
  }

  def processMessage(message: Message)(implicit request: Request[JsValue], hc: HeaderCarrier): Future[Result] = {
    val quadientProperties = message.body.flatMap(_.properties)
    messageConnector.postMessage(request.body).flatMap { _ =>
      cleanUpAndCreateMessage(message.copy(body = message.body.map(d => d.copy(properties = quadientProperties))))
    } recover {
      case Upstream4xxResponse(error) =>
        errorResponseResult(error.message, error.statusCode, showErrorID = true)
      case Upstream5xxResponse(error) =>
        errorResponseResult(error.message, error.statusCode, showErrorID = true)
      case error =>
        errorResponseResult(error.getMessage, INTERNAL_SERVER_ERROR, showErrorID = true)
    }
  }

  private def cleanUpAndCreateMessage(
    message: Message
  )(implicit request: Request[JsValue], hc: HeaderCarrier): Future[Result] =
    cleanUpSubjectAndContent(message).flatMap(m => createMessage(m)).recoverWith {
      case e: HtmlParseException => Future.successful(errorResponseResult(e.getMessage))
      case _ =>
        Future.successful(errorResponseResult("Failed to parse message", INTERNAL_SERVER_ERROR, showErrorID = true))
    }

  def cleanUpSubjectAndContent(message: Message): Future[Message] =
    cleanHtml(message.subject) match {
      case Success(cleanSubject) =>
        val cleaningResult = if (isGmc(message)) {
          cleanHtml(
            message.content.getOrElse(""),
            List(
              AllowedTagAndAttributes("details"),
              AllowedTagAndAttributes("summary"),
              AllowedTagAndAttributes("section", List("lang"))
            )
          )
        } else {
          cleanHtml(
            message.content.getOrElse(""),
            List(
              AllowedTagAndAttributes("details"),
              AllowedTagAndAttributes("summary")
            )
          )
        }
        cleaningResult match {
          case Success(cleanContent) =>
            Future.successful(message.copy(subject = cleanSubject, content = Some(cleanContent)))
          case Failure(e) => Future.failed(HtmlParseException("Failed to parse content HTML", e.getCause))
        }
      case Failure(e) => Future.failed(HtmlParseException("Failed to parse subject HTML", e.getCause))
    }

  private[validators] def cleanHtml(
    dirtyHtml: String,
    extraAllowedTags: List[AllowedTagAndAttributes] = List()
  ): Try[String] = {
    val settings = new OutputSettings().prettyPrint(false).syntax(OutputSettings.Syntax.xml)
    Try(Jsoup.clean(dirtyHtml, "", relaxedAllowlistWithClassAttributes(extraAllowedTags), settings))
  }

  private def relaxedAllowlistWithClassAttributes(extraTags: List[AllowedTagAndAttributes]): Safelist = {
    // format: off
    // We want to allow "class" for all allowed tags. Unfortunately there is no way to do
    // getTags() on a Allowlist, so I have copied the list of tags from the Allowlist.relaxed()
    // implementation here. Obviously this is not ideal, but there isn't a way around it.
    val allTags = List(
      "a", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup", "dd", "div", "dl", "dt", "em", "h1",
      "h2", "h3", "h4", "h5", "h6", "i", "img", "li", "ol", "p", "pre", "q", "small", "span", "strike", "strong", "sub",
      "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul"
    )
    // format: on
    val allTagsAndAttributes = allTags.map(t => AllowedTagAndAttributes(t))
    (allTagsAndAttributes ++ extraTags).foldLeft(Safelist.relaxed()) { (allowlist, tagAndAttributes) =>
      val attributes = "class" :: tagAndAttributes.attributes
      allowlist.addAttributes(tagAndAttributes.tag, attributes*)
    }
  }

  private def createMessage(message: Message)(implicit request: Request[JsValue], hc: HeaderCarrier): Future[Result] =
    for {
      messageWithTaxpayerName <- addTaxpayerNameToMessageIfRequired(message)
      isUnique                <- messageRepository.insertIfUnique(messageWithTaxpayerName)
    } yield
      if (isUnique) {
        val messageId = messageWithTaxpayerName.id.toString
        auditCreateMessageFor(EventTypes.Succeeded, messageWithTaxpayerName, "Message Created")
        Created(Json.obj("id" -> messageId))
      } else {
        val ref = message.externalRef.map(_.source).getOrElse("none")
        logger.warn(s"Duplicate message with ref $ref has not been stored")
        auditCreateMessageFor(EventTypes.Failed, messageWithTaxpayerName, "Message Duplicated")
        errorResponseResult(
          "The backend has rejected the message due to duplicated message content or external reference ID.",
          CONFLICT,
          showErrorID = true
        )
      }

  private def addTaxpayerNameToMessageIfRequired(message: Message)(implicit hc: HeaderCarrier): Future[Message] =
    message.alertDetails.recipientName match {
      case Some(_) => Future.successful(message)
      case None =>
        taxpayerNameConnector
          .taxpayerName(SaUtr(message.recipient.identifier.value))
          .flatMap(name =>
            Future.successful(message.copy(alertDetails = message.alertDetails.copy(recipientName = name)))
          )
    }

  private def auditCreateMessageFor(auditType: String, m: Message, transactionName: String)(implicit
    hc: HeaderCarrier,
    request: Request[JsValue]
  ): Future[Unit] = {

    val params = Map(
      "batchId"     -> m.body.flatMap(_.batchId),
      "replyTo"     -> m.body.flatMap(_.replyTo),
      "threadId"    -> m.body.flatMap(_.threadId),
      "enquiryType" -> m.body.flatMap(_.enquiryType),
      "adviser"     -> m.body.flatMap(_.adviser).map(_.pidId),
      "topic"       -> m.body.flatMap(_.topic)
    )

    audit
      .sendEvent(
        DataEvent(
          auditSource = appName,
          auditType = auditType,
          tags = Map("transactionName" -> transactionName),
          detail = Map(
            "messageId"                 -> m.id.toString,
            "formId"                    -> m.body.flatMap(_.form).getOrElse(""),
            "messageType"               -> m.body.flatMap(_.`type`).getOrElse(m.alertDetails.templateId),
            m.recipient.identifier.name -> m.recipient.identifier.value,
            "originalRequest" -> {
              val requestStr = Json.stringify(request.body)
              if (requestStr.length > auditEventMaxSize) {
                val truncatedRequest = handleBiggerContent(request.body)
                if (truncatedRequest.length > auditEventMaxSize) {
                  "request is too big even without content and sourceData"
                } else {
                  truncatedRequest
                }
              } else {
                requestStr
              }
            }
          ) ++ params.collect { case (k, Some(v)) => k -> v } ++ getOptionalTagValue(NotificationType, m.tags)
        )
      )
      .map {
        case AuditResult.Disabled => logger.warn(s"Audit disabled for create message with id: ${m.id.toString}")
        case AuditResult.Success  => logger.trace("Successful Audit for create message")
        case AuditResult.Failure(msg, _) =>
          logger.error(s"Unable to send an audit event for messageId: ${m.id.toString} : $msg")
      }
  }

  private def getOptionalTagValue(key: String, tags: Option[Map[String, String]]): Map[String, String] =
    (for {
      m <- tags
      v <- m.get(key)
    } yield (key, v)).toMap

  def buildBadRequest(errorMessage: String)(implicit request: Request[JsValue], hc: HeaderCarrier): Result = {
    logger.error(s"Bad request: reason: $errorMessage")
    auditCreateMessageForFailure(errorMessage)
    errorResponseResult(errorMessage, showErrorID = true)
  }

  def auditMessageDeliveryStatus(event: EventHubEvent)(implicit hc: HeaderCarrier): Future[AuditResult] = {
    val detail = Map(
      "messageId"    -> event.event.getMessageId.getOrElse(""),
      "emailAddress" -> event.event.emailAddress,
      "reason"       -> event.event.reason,
      "eventTime"    -> event.timestamp.toString,
      "evenType"     -> event.event.getEventType.toString
    )

    audit.sendEvent(
      DataEvent(
        appName,
        EventTypes.Succeeded,
        tags = hc.toAuditTags(event.event.event.toUpperCase, "external-message-adapter/EventHubProcessor"),
        detail = hc.toAuditDetails(detail.toSeq*)
      )
    )
  }

  def auditCreateMessageForFailure(transactionName: String)(implicit
    hc: HeaderCarrier,
    request: Request[JsValue]
  ): Future[Unit] =
    audit
      .sendEvent(
        DataEvent(
          auditSource = appName,
          auditType = EventTypes.Failed,
          tags = Map("transactionName" -> transactionName),
          detail = Map(
            "originalRequest" -> {
              val requestStr = Json.stringify(request.body)
              if (requestStr.length > auditEventMaxSize) {
                val truncatedRequest = handleBiggerContent(request.body)
                if (truncatedRequest.length > auditEventMaxSize) {
                  "request is too big even without content and sourceData"
                } else {
                  truncatedRequest
                }
              } else {
                requestStr
              }
            }
          )
        )
      )
      .map {
        case AuditResult.Disabled => logger.warn(s"Audit disabled for request id: ${request.id}")
        case AuditResult.Success  => logger.trace("Successful Audit for failed request")
        case AuditResult.Failure(msg, _) =>
          logger.error(s"Unable to send an audit event for messageId: ${request.id} : $msg")
      }

  def auditCreateMessageForFailure(transactionName: String, responseValue: String, statusCode: String)(implicit
    hc: HeaderCarrier,
    request: Request[JsValue]
  ): Future[Unit] =
    audit
      .sendEvent(
        DataEvent(
          auditSource = appName,
          auditType = EventTypes.Failed,
          tags = Map("transactionName" -> transactionName),
          detail = Map(
            "originalRequest" -> {
              val requestStr = Json.stringify(request.body)
              if (requestStr.length > auditEventMaxSize) {
                val truncatedRequest = handleBiggerContent(request.body)
                if (truncatedRequest.length > auditEventMaxSize) {
                  "request is too big even without content and sourceData"
                } else {
                  truncatedRequest
                }
              } else {
                requestStr
              }
            },
            "statusCode" -> statusCode,
            "response"   -> responseValue
          )
        )
      )
      .map {
        case AuditResult.Disabled => logger.warn(s"Audit disabled for request id: ${request.id}")
        case AuditResult.Success  => logger.trace("Successful Audit for failed request")
        case AuditResult.Failure(msg, _) =>
          logger.error(s"Unable to send an audit event for messageId: ${request.id} : $msg")
      }

  def handleBiggerContent(body: JsValue): String = {
    val sourceDataAlternativeText = "sourceData is removed to reduce size"
    val contentAlternativeText = "content is removed to reduce size"
    val bodyObj = body.as[JsObject]
    Json.stringify((bodyObj.keys.contains("sourceData"), bodyObj.keys.contains("content")) match {
      case (true, true) =>
        bodyObj ++ Json.obj("sourceData" -> sourceDataAlternativeText, "content" -> contentAlternativeText)
      case (false, true) => bodyObj ++ Json.obj("content" -> contentAlternativeText)
      case (true, false) => bodyObj ++ Json.obj("sourceData" -> sourceDataAlternativeText)
      case _             => bodyObj
    })
  }

  val checkEnrolmentsEnabled: Boolean = configuration.getOptional[Boolean]("quadient.check.enrolments").getOrElse(true)

}
