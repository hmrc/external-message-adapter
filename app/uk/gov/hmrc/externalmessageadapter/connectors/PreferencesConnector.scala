/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import play.api.Logging
import play.api.http.Status.{ NOT_FOUND, OK }
import play.api.libs.json.{ Json, OFormat }
import uk.gov.hmrc.common.message.model.TaxEntity
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import play.api.libs.ws.writeableOf_String

import java.net.{ URI, URL }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

sealed trait VerifiedEmailAddressResponse extends Product with Serializable {

  def value: Either[VerifiedEmailNotFound, EmailValidation] =
    this match {
      case valid: EmailValidation          => Right(valid)
      case notFound: VerifiedEmailNotFound => Left(notFound)
    }
}

case class EmailValidation(email: String) extends VerifiedEmailAddressResponse

object EmailValidation {
  implicit val format: OFormat[EmailValidation] = Json.format[EmailValidation]
}

case class VerifiedEmailNotFound(reasonCode: String) extends VerifiedEmailAddressResponse {
  def getMessage: String = reasonCode match {
    case "EMAIL_ADDRESS_NOT_VERIFIED" =>
      "The backend has rejected the message due to not being able to verify the email address."
    case "NOT_OPTED_IN"          => "email: not verified as user not opted in"
    case "PREFERENCES_NOT_FOUND" => "email: not verified as preferences not found"
    case _                       => "email: not verified for unknown reason"
  }
}

case class OtherException(message: String) extends Exception(message)

@Singleton
class PreferencesConnector @Inject() (
  http: HttpClientV2,
  servicesConfig: ServicesConfig
)(implicit ec: ExecutionContext)
    extends Logging with UriValueEncoder {

  val baseUrl: String = servicesConfig.baseUrl("preferences")

  def url(path: String): URL = URI(s"$baseUrl$path").toURL

  def verifiedEmailAddress(
    taxEntity: TaxEntity
  )(implicit hc: HeaderCarrier): Future[VerifiedEmailAddressResponse] =
    http
      .get(url(s"/preferences/verified-email?regime=${taxEntity.regime}&taxId=${encode(taxEntity.identifier.value)}"))
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case OK        => response.json.as[EmailValidation]
          case NOT_FOUND => VerifiedEmailNotFound(response.body)
          case status    => throw OtherException(s"OTHER_EXCEPTION_$status")
        }
      }

  def markPreferencesForDeEnrolment(taxEntity: TaxEntity)(implicit hc: HeaderCarrier): Future[HttpResponse] =
    http
      .put(
        url(
          s"/preferences/mark-for-de-enrolment?taxRegime=${taxEntity.regime}&taxId=${encode(taxEntity.identifier.value)}"
        )
      )
      .withBody[String]("")
      .execute[HttpResponse]

  def unsetMarkForDeEnrolment(taxEntity: TaxEntity)(implicit hc: HeaderCarrier): Future[HttpResponse] =
    http
      .put(
        url(
          s"/preferences/unset-de-enrolment?taxRegime=${taxEntity.regime}&taxId=${encode(taxEntity.identifier.value)}"
        )
      )
      .withBody[String]("")
      .execute[HttpResponse]

}
