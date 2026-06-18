/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import org.scalatest.Inside
import org.scalatestplus.play.PlaySpec
import play.api.libs.json._
import uk.gov.hmrc.domain.TaxIds.TaxIdWithName
import uk.gov.hmrc.domain._
import uk.gov.hmrc.common.message.model.MongoTaxIdentifierFormats._

class TaxIdentifierMongoFormatsSpec extends PlaySpec {
  "Mongo JSON Formats for TaxIdWithName" must {
    "write a CTUTR as an element with name and value fields" in {
      val json = Json.toJson[TaxIdWithName](CtUtr("1234554321"))
      json mustBe Json.parse("""{"name": "ctutr", "value": "1234554321"}""")
    }

    "write a VRN as an element with name and value fields" in {
      val json = Json.toJson[TaxIdWithName](Vrn("123455432"))
      json mustBe Json.parse("""{"name": "vrn", "value" :"123455432"}""")
    }

    "write a UAR as an element with name and value fields" in {
      val json = Json.toJson[TaxIdWithName](Uar("aaa/bbb"))
      json mustBe Json.parse("""{"name": "uar", "value": "aaa/bbb"}""")
    }

    "write an SAUTR as an element with name and value fields" in {
      val json = Json.toJson[TaxIdWithName](SaUtr("1234554321"))
      json mustBe Json.parse("""{ "name": "sautr", "value": "1234554321"}""")
    }

    "write a NINO as an element with name and value fields" in {
      val json = Json.toJson[TaxIdWithName](Nino("AB112233B"))
      json mustBe Json.parse("""{"name": "nino", "value": "AB112233B"}""")
    }

    "support reading an SAUTR from json" in {
      val json = Json.parse("""{"name": "sautr", "value": "1234554321" }""")
      Json.fromJson[TaxIdWithName](json) mustBe JsSuccess(SaUtr("1234554321"))
    }

    "support reading a NINO from json" in {
      val json = Json.parse("""{"name": "nino", "value": "AB112233B"}""")
      Json.fromJson[TaxIdWithName](json) mustBe JsSuccess(Nino("AB112233B"))
    }

    "fail when reading an element with an unknown types" in {
      val json = Json.parse("""{"name": "bbb", "value": "aaa"}""")
      Inside.inside(Json.fromJson[TaxIdWithName](json)) { case JsError(errors) =>
        errors.head._2.head mustBe JsonValidationError(
          "could not determine tax id with name = bbb and value = aaa"
        )
      }
    }
  }
}
