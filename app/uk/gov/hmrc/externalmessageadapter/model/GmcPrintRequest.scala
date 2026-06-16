/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.Logging
import play.api.libs.json.{ JsValue, Json, OFormat }
import uk.gov.hmrc.externalmessageadapter.model.GmcPrintResponse.UNKNOWN_EIS_ERROR
import uk.gov.hmrc.common.message.model.Message

case class GmcPrintRequest(
  reason: String,
  sourceData: String,
  emailAddress: String,
  formId: Option[String] = None,
  properties: Option[JsValue] = None
)

object GmcPrintRequest extends Logging {
  implicit val format: OFormat[GmcPrintRequest] = Json.format[GmcPrintRequest]

  def fromMessage(
    reason: String,
    message: Message,
    emailAddress: String,
    properties: Option[JsValue] = None
  ): Option[GmcPrintRequest] =
    message.sourceData.map { sourceData =>
      val gmcRequest =
        GmcPrintRequest(
          reason,
          sourceData,
          emailAddress,
          message.body.flatMap(_.form).map(_.filterNot(_.isWhitespace)),
          properties
        )
      logger debug s"EventHub Processor: >>>GMCPrint Request $gmcRequest"
      gmcRequest
    }
}

case class GmcPrintResponse(status: Int, message: String)
case class GmcPrintResponseBody(failures: List[GmcPrintFailureResponse]) {
  def toGmcPrintResponse(status: Int): GmcPrintResponse = {
    val message = failures.headOption.map(_.reason).getOrElse(UNKNOWN_EIS_ERROR)
    GmcPrintResponse(status, message)
  }
}

object GmcPrintResponseBody {
  implicit val failureFormat: OFormat[GmcPrintFailureResponse] = Json.format[GmcPrintFailureResponse]
  implicit val format: OFormat[GmcPrintResponseBody] = Json.format[GmcPrintResponseBody]

}
case class GmcPrintFailureResponse(reason: String, code: Option[String])

object GmcPrintResponse {

  val UNKNOWN_EIS_ERROR = "Unknown eis error"
  def unknownGmcPrintResponse(status: Int): GmcPrintResponse =
    GmcPrintResponse(status, UNKNOWN_EIS_ERROR)

}
