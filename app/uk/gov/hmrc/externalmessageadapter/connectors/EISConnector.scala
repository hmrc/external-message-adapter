/*
 * Copyright 2026 HM Revenue & Customs
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

import play.api.Logger
import play.api.http.HeaderNames.{ ACCEPT, AUTHORIZATION, CONTENT_TYPE, DATE }
import play.api.http.Status.NOT_IMPLEMENTED
import play.api.http.{ MimeTypes, Status }
import play.api.http.Status.{ BAD_REQUEST, FORBIDDEN, INTERNAL_SERVER_ERROR, NOT_FOUND, OK, REQUEST_TIMEOUT, SERVICE_UNAVAILABLE, UNAUTHORIZED }
import play.api.libs.json.Json
import uk.gov.hmrc.externalmessageadapter.model.{ EmailBounce4xxResponse, GmcPrintRequest, GmcPrintResponse, GmcPrintResponseBody }
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, StringContextOps }
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.externalmessageadapter.utils.Util.{ COLON, COMMA_WITH_SPACE, EMPTY_STRING, encodeStringToBase64, uuidOfLength32 }

import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{ ZoneOffset, ZonedDateTime }
import javax.inject.{ Inject, Named, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class EISConnector @Inject() (
  httpClient: HttpClientV2,
  servicesConfig: ServicesConfig,
  @Named("bouncebackFormIds") bouncebackFormIds: Seq[String]
)(implicit ec: ExecutionContext) {

  val logger: Logger = Logger(this.getClass)

  private val isHipProcessingEnabled: Boolean = servicesConfig.getConfBool("hip.email-bounce-back.enabled", false)

  def post(gmcPrintRequest: GmcPrintRequest, correlationId: String): Future[Option[GmcPrintResponse]] = {
    logger.debug(
      s"EventHub Processor: CorrelationId - $correlationId with gmcPrintRequest details for ${gmcPrintRequest.reason}"
    )

    implicit val hc: HeaderCarrier = HeaderCarrier()

    if (isFormIdEligibleToBeProcessedByHIP(gmcPrintRequest.formId.getOrElse(EMPTY_STRING)) && isHipProcessingEnabled) {
      processRequestOverHIP(gmcPrintRequest)
    } else {
      processRequestOverEIS(gmcPrintRequest, correlationId)
    }
  }

  private def processRequestOverEIS(gmcPrintRequest: GmcPrintRequest, correlationId: String)(implicit
    hc: HeaderCarrier
  ) = {
    val eisBaseUrl = servicesConfig.baseUrl("eis")
    val eisBearerToken = servicesConfig.getString("microservice.services.eis.bearer-token")
    val eisEndpoint = servicesConfig.getString("microservice.services.eis.endpoint")
    val eisEnvironment = servicesConfig.getString("microservice.services.eis.environment")

    val eisEndPointUrl = s"$eisBaseUrl$eisEndpoint"

    httpClient
      .post(url"$eisEndPointUrl")
      .withBody(Json.toJson(gmcPrintRequest.copy(externalRefId = None)))
      .setHeader(
        (CONTENT_TYPE, MimeTypes.JSON),
        (ACCEPT, MimeTypes.JSON),
        (AUTHORIZATION, s"Bearer $eisBearerToken"),
        (DATE, DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))),
        (CustomHeaders.CorrelationId, correlationId),
        (CustomHeaders.Environment, eisEnvironment)
      )
      .execute[HttpResponse]
      .map {
        case resp if resp.status == OK =>
          val s: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.warn(s">>>GmcPrintRequest OK, CorrelationId - $correlationId" + s)
          None

        case resp if resp.status == BAD_REQUEST =>
          val s: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.debug(s">>>GmcPrintRequest BAD_REQUEST, CorrelationId - $correlationId" + s + resp.body)
          resp.json
            .asOpt[GmcPrintResponseBody]
            .map(_.toGmcPrintResponse(resp.status))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponse(resp.status)))

        case resp =>
          val s: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.debug(s">>>GmcPrintRequest OTHER, CorrelationId - $correlationId" + s + resp.body)
          resp.json
            .asOpt[GmcPrintResponseBody]
            .map(_.toGmcPrintResponse(resp.status))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponse(resp.status)))
      }
  }

  private def isFormIdEligibleToBeProcessedByHIP(formId: String): Boolean =
    bouncebackFormIds.contains(formId.toUpperCase)

  private def processRequestOverHIP(
    gmcPrintRequest: GmcPrintRequest
  )(implicit hc: HeaderCarrier): Future[Option[GmcPrintResponse]] = {
    val hipBaseUrl = servicesConfig.baseUrl("hip")
    val hipClientId = servicesConfig.getString("microservice.services.hip.email-bounce-back.client-id")
    val hipClientSecret = servicesConfig.getString("microservice.services.hip.email-bounce-back.client-secret")
    val hipEndpoint = servicesConfig.getString("microservice.services.hip.email-bounce-back.endPoint")

    val hipEndPointUrl = s"$hipBaseUrl$hipEndpoint"
    val correlationId = uuidOfLength32
    val authToken = encodeStringToBase64(s"$hipClientId$COLON$hipClientSecret")

    httpClient
      .post(url"$hipEndPointUrl")
      .withBody(Json.toJson(gmcPrintRequest))
      .setHeader(
        (CONTENT_TYPE, MimeTypes.JSON),
        (ACCEPT, MimeTypes.JSON),
        (AUTHORIZATION, s"Basic $authToken"),
        (CustomHeaders.CorrelationIdHIP, correlationId)
      )
      .execute[HttpResponse]
      .map {
        case resp if resp.status == Status.OK =>
          val responseHeaders: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.warn(s">>>GmcPrintRequest OK, CorrelationId - $correlationId $responseHeaders")

          None

        case resp if resp.status == BAD_REQUEST =>
          val responseHeaders: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.warn(s">>>GmcPrintRequest BAD_REQUEST, CorrelationId - $correlationId $responseHeaders ${resp.body}")

          resp.json
            .asOpt[GmcPrintResponseBody]
            .map(_.toGmcPrintHIPResponse(resp.status))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponseFromHip(resp.status)))

        case resp if isResponseCode5xx(resp.status) =>
          val responseHeaders: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.warn(s">>>GmcPrintRequest BAD_REQUEST, CorrelationId - $correlationId $responseHeaders ${resp.body}")

          resp.json
            .asOpt[GmcPrintResponseBody]
            .map(_.toGmcPrintHIPResponse(resp.status))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponseFromHip(resp.status)))

        case resp if isResponseCode4xx(resp.status) =>
          val responseHeaders: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(COMMA_WITH_SPACE)
          logger.warn(
            s">>>GmcPrintRequest response code ${resp.status}, CorrelationId - $correlationId $responseHeaders ${resp.body}"
          )
          resp.json
            .asOpt[EmailBounce4xxResponse]
            .map(emailBounceResponse => GmcPrintResponse(resp.status, emailBounceResponse.message))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponseFromHip(resp.status)))
      }
      .recover { case _ =>
        logger.error("Either unexpected HIP response or technical error occurred")
        Option(GmcPrintResponse.unknownGmcPrintResponseFromHip(NOT_IMPLEMENTED))
      }
  }

  private def isResponseCode4xx(responseStatus: Int): Boolean =
    List(UNAUTHORIZED, FORBIDDEN, NOT_FOUND, REQUEST_TIMEOUT).contains(responseStatus)

  private def isResponseCode5xx(responseStatus: Int) =
    responseStatus == INTERNAL_SERVER_ERROR || responseStatus == SERVICE_UNAVAILABLE
}

object CustomHeaders {
  val CorrelationId = "X-Correlation-ID"
  val CorrelationIdHIP = "correlationid"
  val ForwardedHost = "X-Forwarded-Host"
  val EisSenderClassification = "X-Eis-Sender-Classification"
  val Environment = "environment"
}
