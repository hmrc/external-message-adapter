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
