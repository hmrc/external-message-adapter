/*
 * Copyright 2024 HM Revenue & Customs
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
