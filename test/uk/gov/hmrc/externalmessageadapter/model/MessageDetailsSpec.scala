/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import uk.gov.hmrc.common.message.model.MessageDetails

class MessageDetailsSpec extends PlaySpec {

  "MessageDetails" must {

    "deserialise valid properties" in {

      val randomJsValue = Json.obj("randomKey" -> "randomValue")
      val json =
        Json.obj(
          "formId"     -> "formId",
          "properties" -> randomJsValue
        )

      json.as[MessageDetails].properties mustBe Some(randomJsValue)
    }

    "set properties to None, when not provided" in {
      val json =
        Json.obj(
          "formId" -> "formId"
        )

      json.as[MessageDetails].properties mustBe None

    }
  }

}
