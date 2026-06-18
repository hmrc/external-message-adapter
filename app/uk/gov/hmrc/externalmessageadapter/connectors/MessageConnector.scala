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

import play.api.{ Configuration, Logging }
import play.api.libs.json.JsValue
import uk.gov.hmrc.externalmessageadapter.validators.SecureMessageUtil.*
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import play.api.libs.ws.writeableOf_JsValue
import play.mvc.Http.Status

import java.net.URI
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class MessageConnector @Inject() (httpClient: HttpClientV2, servicesConfig: ServicesConfig)(implicit
  ec: ExecutionContext
) extends Logging {

  val secureMessageBackendUrl: String = s"${servicesConfig.baseUrl("secure-message")}/secure-messaging/v4/message"

  val requestBodyV4 = (body: JsValue) => createSecureMessage(body)

  def postMessage(body: JsValue)(implicit hc: HeaderCarrier): Future[HttpResponse] = {
    logger.warn(s"CreateMessage: post message to $secureMessageBackendUrl")
    httpClient
      .post(URI(secureMessageBackendUrl).toURL)
      .withBody(requestBodyV4(body))
      .execute[HttpResponse]
      .flatMap { response =>
        response.status match {
          case Status.OK | Status.CREATED => Future.successful(response)
          case failedStatus               => throw UpstreamErrorResponse(response.body, response.status)
        }
      }
  }
}
