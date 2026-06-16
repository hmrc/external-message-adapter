/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import play.api.Logging
import play.api.http.Status.{ NO_CONTENT, OK }
import play.utils.UriEncoding
import uk.gov.hmrc.common.message.model.Enrolments
import uk.gov.hmrc.externalmessageadapter.model.{ Enrolment, Users }
import uk.gov.hmrc.http
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URI
import javax.inject.Inject
import scala.concurrent.{ ExecutionContext, Future }

class EnrolmentProxyConnector @Inject() (http: HttpClientV2, servicesConfig: ServicesConfig)(implicit
  ec: ExecutionContext
) extends Logging with UriValueEncoder {

  def enrolments(
    enrolments: Enrolments
  )(implicit hc: HeaderCarrier): Future[Either[String, (String, Option[Users])]] = {
    val enrolment = enrolments.main

    def loop(enrolmentKey: String, fallBack: List[String]): Future[Either[String, (String, Option[Users])]] =
      http
        .get(URI(s"$serviceBaseUrl/enrolment-store/enrolments/${encode(enrolmentKey)}/users").toURL)
        .execute[HttpResponse]
        .flatMap { resp =>
          resp.status match {
            case OK => Future.successful(Right(enrolmentKey -> Some(resp.json.as[Users])))
            case NO_CONTENT =>
              if (fallBack.isEmpty) {
                Future.successful(Right(enrolmentKey -> None))
              } else {
                loop(fallBack.head, fallBack.tail)
              }
            case _ =>
              if (fallBack.isEmpty) {
                Future.successful(Left(s"Invalid enrolment key $enrolment"))
              } else {
                loop(fallBack.head, fallBack.tail)
              }
          }
        }
    loop(enrolment, enrolments.fallback)
  }
  def hasActiveUsers(userIds: List[String], enrolmentKey: String)(implicit hc: HeaderCarrier): Future[Boolean] =
    userIds.foldLeft(Future.successful(false)) { (active, userId) =>
      active.flatMap { a =>
        isActiveUser(userId, enrolmentKey).map(_ || a)
      }
    }

  private def isActiveUser(userId: String, enrolmentKey: String)(implicit hc: HeaderCarrier): Future[Boolean] =
    http
      .get(URI(s"$serviceBaseUrl/enrolment-store/users/$userId/enrolments/${encode(enrolmentKey)}").toURL)
      .execute[HttpResponse]
      .map { resp =>
        resp.status match {
          case OK => resp.json.as[Enrolment].isActivated
          case _ =>
            logger.warn(s"Invalid user $userId")
            false
        }
      }

  def serviceBaseUrl: String = s"${servicesConfig.baseUrl("enrolment-store-proxy")}/enrolment-store-proxy"
}
