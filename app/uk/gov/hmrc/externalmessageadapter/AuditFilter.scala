/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter

import org.apache.pekko.stream.Materializer
import play.api.Configuration
import play.api.libs.json.{ JsObject, JsString }
import play.api.mvc.RequestHeader
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.hooks.Data
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ ExtendedDataEvent, RedactionLog, TruncationLog }
import uk.gov.hmrc.play.bootstrap.backend.filters.DefaultBackendAuditFilter
import uk.gov.hmrc.play.bootstrap.config.{ ControllerConfigs, HttpAuditEvent }
import uk.gov.hmrc.play.bootstrap.filters.Details

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class AuditFilter @Inject() (
  override val config: Configuration,
  val controllerConfigs: ControllerConfigs,
  override val auditConnector: AuditConnector,
  httpAuditEvent: HttpAuditEvent,
  override val mat: Materializer
)(implicit ec: ExecutionContext)
    extends DefaultBackendAuditFilter(config, controllerConfigs, auditConnector, httpAuditEvent, mat) {
  override protected def buildRequestDetails(requestHeader: RequestHeader, requestBody: Data[String]): Details = {
    val detailsMap = Map(
      EventKeys.RequestBody -> requestBody.value,
      "CorrelationId"       -> requestHeader.headers.get("CorrelationId").getOrElse("-")
    )

    Details(
      details = JsObject(detailsMap.map(item => (item._1, JsString(item._2))).toSeq),
      TruncationLog.Empty,
      RedactionLog.of(List.empty)
    )
  }

  override def extendedDataEvent(
    eventType: String,
    transactionName: String,
    request: RequestHeader,
    detail: JsObject,
    truncationLog: TruncationLog,
    redactionLog: RedactionLog
  )(implicit hc: HeaderCarrier): ExtendedDataEvent =
    super.extendedDataEvent(eventType, transactionName, request, detail, truncationLog, redactionLog)
}
