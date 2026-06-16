/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers

import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.libs.json.*
import play.api.mvc.*
import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.common.message.DateValidationException
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.common.message.model.Message
import uk.gov.hmrc.common.message.validationmodule.MessageValidator.isValidMessage
import uk.gov.hmrc.externalmessageadapter.validators.SecureMessageUtil.*
import uk.gov.hmrc.externalmessageadapter.validators.MessagesUtil
import uk.gov.hmrc.common.message.failuremodule.FailureResponseService.errorResponseResult
import play.api.mvc.Result
import play.api.http.HttpEntity

import javax.inject.Inject
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

class MessagesController @Inject() (
  cc: MessagesControllerComponents,
  messagesUtil: MessagesUtil
)(implicit ec: ExecutionContext)
    extends BackendController(cc) with Logging with I18nSupport {

  def createMessage(): Action[JsValue] = Action.async(parse.json) { implicit request =>
    import uk.gov.hmrc.externalmessageadapter.controllers.MessageRESTFormatsV4._

    def doCheckEnrolments(message: Message): Boolean =
      messagesUtil.checkEnrolmentsEnabled && !message.recipient.identifier.isInstanceOf[Nino]

    withJsonBody[Message] { message =>
      val response = isValidMessageRequest(message) match {
        case Success(_) if doCheckEnrolments(message) =>
          messagesUtil.checkEnrolmentsAndProcessMessage(message)
        case Success(_) if message.recipient.email.isEmpty =>
          logger.debug(s"CreateMessage: ${message.recipient}")
          messagesUtil.checkTaxpayerAndProcessMessage(message)
        case Success(_) =>
          logger.debug(s"CreateMessage: process message${message.recipient}")
          messagesUtil.processMessage(message)
        case Failure(e) =>
          logger.warn(s"CreateMessage: BadRequest ${e.getMessage}")
          Future.successful(errorResponseResult(e.getMessage(), showErrorID = true))
      }

      response.map { result =>
        if (result.header.status >= 400 && result.header.status <= 599 && result.header.status != 409) {
          def extractBodyAsString(result: Result): String =
            result.body match {
              case HttpEntity.Strict(data, _) =>
                data.decodeString("UTF-8")
              case _ => ""
            }

          messagesUtil
            .auditCreateMessageForFailure("TxFailed", extractBodyAsString(result), result.header.status.toString)
        }
      }

      response
    }
  }

  private def isValidMessageRequest(message: Message): Try[Message] =
    for {
      _ <- isValidMessage(message)
      _ <- checkValidContent(message)
    } yield message

  override protected def withJsonBody[T](
    f: T => Future[Result]
  )(implicit request: Request[JsValue], ct: ClassTag[T], reads: Reads[T]): Future[Result] =
    Try(request.body.validate[T]) match {
      case Success(JsSuccess(payload, _)) =>
        f(payload)

      case Success(JsError(errs)) =>
        Future.successful(
          errorResponseResult(
            errs.headOption.fold("Unknown") { case (_, validationErrors) =>
              val errorMessage = validationErrors.headOption.fold("Unknown") { errors =>
                errors.messages
                  .find(_.startsWith("The backend has rejected the message due to an unknown tax identifier."))
                  .getOrElse(errors.messages.toString)
              }
              messagesUtil.auditCreateMessageForFailure(errorMessage)
              errorMessage
            },
            showErrorID = true
          )
        )

      case Failure(e) if e.isInstanceOf[DateValidationException] =>
        Future.successful(messagesUtil.buildBadRequest(e.getMessage))

      case Failure(e) => Future.successful(messagesUtil.buildBadRequest(s"could not parse body due to ${e.getMessage}"))
    }

}
