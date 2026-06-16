/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.util

import play.api.libs.json.{ JsValue, Json }

import scala.io.Source

object Resources {
  def readJson(fileName: String): JsValue = {
    val resource = Source.fromURL(getClass.getResource("/" + fileName))
    val json = Json.parse(resource.mkString)
    resource.close()
    json
  }
}
