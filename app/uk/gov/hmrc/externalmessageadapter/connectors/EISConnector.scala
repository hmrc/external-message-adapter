/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import play.api.Logger
import play.api.http.HeaderNames.{ ACCEPT, AUTHORIZATION, CONTENT_TYPE, DATE }
import play.api.http.{ MimeTypes, Status }
import play.api.libs.json.Json
import uk.gov.hmrc.externalmessageadapter.model.{ GmcPrintRequest, GmcPrintResponse, GmcPrintResponseBody }
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import play.api.libs.ws.writeableOf_JsValue

import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{ ZoneOffset, ZonedDateTime }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
@SuppressWarnings(Array("org.wartremover.warts.ImplicitParameter"))
class EISConnector @Inject() (
  httpClient: HttpClientV2,
  servicesConfig: ServicesConfig
)(implicit ec: ExecutionContext) {

  val logger: Logger = Logger(this.getClass())

  private val eisBaseUrl = servicesConfig.baseUrl("eis")
  private val eisBearerToken = servicesConfig.getString("microservice.services.eis.bearer-token")
  private val eisEndpoint = servicesConfig.getString("microservice.services.eis.endpoint")
  private val eisEnvironment = servicesConfig.getString("microservice.services.eis.environment")

  def post(gmcPrintRequest: GmcPrintRequest, correlationId: String): Future[Option[GmcPrintResponse]] = {
    logger.debug(
      s"EventHub Processor: CorrelationId - $correlationId with gmcPrintRequest details for ${gmcPrintRequest.reason}"
    )
    implicit val hc: HeaderCarrier = HeaderCarrier()
    httpClient
      .post(URI(s"$eisBaseUrl$eisEndpoint").toURL)
      .withBody(Json.toJson(gmcPrintRequest))
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
        case resp if resp.status == Status.OK =>
          val s: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(", ")
          logger.warn(s">>>GmcPrintRequest OK, CorrelationId - $correlationId" + s)
          None
        case resp if resp.status == Status.BAD_REQUEST =>
          val s: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(", ")
          logger.debug(s">>>GmcPrintRequest BAD_REQUEST, CorrelationId - $correlationId" + s + resp.body)
          resp.json
            .asOpt[GmcPrintResponseBody]
            .map(_.toGmcPrintResponse(resp.status))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponse(resp.status)))
        case resp =>
          val s: String = resp.headers.map(i => i._1 + "->" + i._2).mkString(", ")
          logger.debug(s">>>GmcPrintRequest OTHER, CorrelationId - $correlationId" + s + resp.body)
          resp.json
            .asOpt[GmcPrintResponseBody]
            .map(_.toGmcPrintResponse(resp.status))
            .orElse(Some(GmcPrintResponse.unknownGmcPrintResponse(resp.status)))
      }
  }
}

object CustomHeaders {
  val CorrelationId = "X-Correlation-ID"
  val ForwardedHost = "X-Forwarded-Host"
  val EisSenderClassification = "X-Eis-Sender-Classification"
  val Environment = "environment"
}
