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
import play.api.http.HeaderNames.AUTHORIZATION
import play.api.libs.json.*
import uk.gov.hmrc.domain.SaUtr
import uk.gov.hmrc.common.message.model.TaxpayerName
import uk.gov.hmrc.http.{ HeaderCarrier, NotFoundException, StringContextOps }
import uk.gov.hmrc.http.HttpReads.Implicits.readFromJson
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.{ URI, URL }
import java.util.{ Base64, UUID }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class TaxpayerNameConnector @Inject() (
  http: HttpClientV2,
  servicesConfig: ServicesConfig
)(implicit ec: ExecutionContext)
    extends Logging {

  val serviceUrl: String = servicesConfig.baseUrl("taxpayer-data")

  def taxpayerName(utr: SaUtr)(implicit hc: HeaderCarrier): Future[Option[TaxpayerName]] = {

    implicit val nameFormat: OFormat[NameFromHods] = NameFromHods.format

    val taxPayerClient: RequestBuilder =
      if (hipSwitchOn)
        http
          .get(url"$serviceUrlViaHip/ods-sa/v1/self-assessment/individual/$utr/designatory-details/taxpayer")
          .setHeader(requestHeaders: _*)
      else
        http
          .get(url"$serviceUrl/self-assessment/individual/$utr/designatory-details/taxpayer")

    taxPayerClient
      .execute[NameFromHods]
      .map(_.name)
      .recover {
        case notFound: NotFoundException =>
          logger.warn(s"No taxpayer name found for utr: $utr", notFound)
          None
        case e =>
          logger.error(s"Unable to get taxpayer name for $utr", e)
          None
      }
  }

  private val hipSwitchOn: Boolean = servicesConfig.getConfBool("taxpayer-data-hip.enabled", false)
  private val serviceUrlViaHip: String = servicesConfig.baseUrl("taxpayer-data-hip")
  private val requestHeaders = {
    val clientId = servicesConfig.getConfString("taxpayer-data-hip.client-id", "unknown")
    val clientSecret = servicesConfig.getConfString("taxpayer-data-hip.client-secret", "unknown")
    val credentials = s"$clientId:$clientSecret"
    val b64Encoded = Base64.getEncoder.encodeToString(credentials.getBytes("UTF-8"))
    Seq(AUTHORIZATION -> s"Basic $b64Encoded", "correlationId" -> UUID.randomUUID.toString)
  }
}

case class NameFromHods(name: Option[TaxpayerName])

object NameFromHods {

  implicit val taxpayerNameFormat: Format[TaxpayerName] = TaxpayerName.taxpayerNameFormat
  implicit val format: OFormat[NameFromHods] = Json.format[NameFromHods]
}
