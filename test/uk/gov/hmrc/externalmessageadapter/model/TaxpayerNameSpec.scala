/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import uk.gov.hmrc.common.message.model.TaxpayerName

class TaxpayerNameSpec extends PlaySpec {

  val title = "Mr  "
  val forename = "  Geoff"
  val secondForename = "  Bob  "
  val surname = "Fisher   "
  val honours = "LLB    "
  val line1 = "line1    "
  val line2 = "line2    "
  val line3 = "line3    "

  def salutation(name: TaxpayerName): String = {
    def part(namePart: Option[String]): String = namePart.map(" " + _).getOrElse("")

    def salutationName =
      part(name.title) + part(name.forename) + part(name.secondForename) + part(name.surname) + part(name.honours)

    s"Hi$salutationName,"
  }

  "Taxpayer name toMap" must {
    "properly map filled in tax payer names" in {
      val taxpayerGeoff = TaxpayerName(
        title = Some(title),
        forename = Some(forename),
        secondForename = Some(secondForename),
        surname = Some(surname),
        honours = Some(honours),
        line1 = Some(line1),
        line2 = Some(line2),
        line3 = Some(line3)
      )

      taxpayerGeoff.asMap must be(
        Map(
          "recipientName_title"          -> "Mr",
          "recipientName_forename"       -> "Geoff",
          "recipientName_secondForename" -> "Bob",
          "recipientName_surname"        -> "Fisher",
          "recipientName_honours"        -> "LLB",
          "recipientName_line1"          -> "line1",
          "recipientName_line2"          -> "line2",
          "recipientName_line3"          -> "line3"
        )
      )
    }

    "properly map empty tax payer names" in {
      TaxpayerName().asMap must be(Map.empty)
    }

    "use customer for line1 when line1 is empty" in {
      val taxpayerL1empty = TaxpayerName(
        line1 = Some("   ")
      )

      taxpayerL1empty.asMap must be(
        Map(
          "recipientName_line1" -> "Customer"
        )
      )
    }

    "ignore empty name fields" in {
      val taxpayerGeoff = TaxpayerName(
        title = Some(title),
        forename = Some(""),
        secondForename = Some("  "),
        surname = Some(surname),
        honours = Some(honours)
      )

      taxpayerGeoff.asMap must be(
        Map(
          "recipientName_title"   -> "Mr",
          "recipientName_surname" -> "Fisher",
          "recipientName_honours" -> "LLB"
        )
      )

    }
  }

  "Taxpayer name" must {
    "parse full name from json" in {
      val taxPayerName = Json.fromJson[TaxpayerName](Json.parse(s"""{
        "title": "$title",
        "forename": "$forename",
        "secondForename": "$secondForename",
        "surname": "$surname",
        "honours": "$honours"
      }"""))

      taxPayerName.get must be(
        TaxpayerName(
          title = Some(title),
          forename = Some(forename),
          secondForename = Some(secondForename),
          surname = Some(surname),
          honours = Some(honours)
        )
      )
    }

    "parse partial name from json" in {
      val taxPayerName = Json.fromJson[TaxpayerName](Json.parse(s"""{
        "title": "$title",
        "forename": "$forename",
        "surname": "$surname"
      }"""))

      taxPayerName.get must be(
        TaxpayerName(
          title = Some(title),
          forename = Some(forename),
          secondForename = None,
          surname = Some(surname),
          honours = None
        )
      )
    }
  }
}
