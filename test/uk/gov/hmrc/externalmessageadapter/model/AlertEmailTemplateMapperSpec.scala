/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import org.scalatestplus.play.PlaySpec
import uk.gov.hmrc.common.message.model._

class AlertEmailTemplateMapperSpec extends PlaySpec with AlertEmailTemplateMapper {

  "The alert email template mapper" must {

    "use custom templates for atsv2, SA309A, SA316, SA300, SS300, P800, PA302 message alerts" in {
      emailTemplateFromMessageFormId("atsv2") mustBe "annual_tax_summaries_message_alert"
      emailTemplateFromMessageFormId("SA309A") mustBe "newMessageAlert_SA309"
      emailTemplateFromMessageFormId("SA316") mustBe "newMessageAlert_SA316"
      emailTemplateFromMessageFormId("SA300") mustBe "newMessageAlert_SA300"
      emailTemplateFromMessageFormId("SS300") mustBe "newMessageAlert_SS300"
      emailTemplateFromMessageFormId("P800 2032") mustBe "newMessageAlert_P800"
      emailTemplateFromMessageFormId("PA302 2032") mustBe "newMessageAlert_PA302"
    }

    "map all the SA not custom templates to `newMessageAlert_formId`" in {
      templatesToMapToNewMessageAlert.foreach { t =>
        emailTemplateFromMessageFormId(t) mustBe s"newMessageAlert_$t"
      }
    }

    "match on form ids with extra details message alerts" in {
      emailTemplateFromMessageFormId("SA309A July 2017") mustBe "newMessageAlert_SA309"
      emailTemplateFromMessageFormId("SA316 (Batch 5)") mustBe "newMessageAlert_SA316"
    }

    "use a standard template for other message alerts" in {
      emailTemplateFromMessageFormId("SAXXX") mustBe "newMessageAlert"
      emailTemplateFromMessageFormId("SAYYY") mustBe "newMessageAlert"
    }

    "not override the alert template only when alert template is different from newMessageAlert" in {
      emailTemplateFromMessageFormId("SA309A", Some("myTemplateId")) mustBe "myTemplateId"
      emailTemplateFromMessageFormId("SA309A", Some("newMessageAlert")) mustBe "newMessageAlert_SA309"
      emailTemplateFromMessageFormId("SA309A") mustBe "newMessageAlert_SA309"
    }

    "map all the ITSA not custom templates to `new_message_alert_itsa`" in {
      val itsaFormId = Map(
        "ITSAQU1"    -> "new_message_alert_itsaqu1",
        "ITSAQU2"    -> "new_message_alert_itsaqu2",
        "ITSAEOPS1"  -> "new_message_alert_itsaeops1",
        "ITSAEOPS2"  -> "new_message_alert_itsaeops2",
        "ITSAEOPSF"  -> "new_message_alert_itsaeopsf",
        "ITSAPOA1-1" -> "new_message_alert_itsapoa1-1",
        "ITSAPOA1-2" -> "new_message_alert_itsapoa1-2",
        "ITSAPOA2-1" -> "new_message_alert_itsapoa2-1",
        "ITSAPOA2-2" -> "new_message_alert_itsapoa2-2",
        "ITSAFD1"    -> "new_message_alert_itsafd1",
        "ITSAFD2"    -> "new_message_alert_itsafd2",
        "ITSAFD3"    -> "new_message_alert_itsafd3",
        "ITSAPOA-CN" -> "new_message_alert_itsapoa-cn",
        "ITSAUC1"    -> "new_message_alert_itsauc1"
      )
      itsaFormId.foreach { t =>
        emailTemplateFromMessageFormId(t._1) mustBe t._2
      }
    }
  }

  "The alert email template mapper - Welsh" must {

    "use custom templates for atsv2, SA309A, SA316, SA300, SS300, P800, PA302 message alerts" in {
      emailTemplateFromMessageFormId("atsv2_cy") mustBe "annual_tax_summaries_message_alert_cy"
      emailTemplateFromMessageFormId("SA309A_CY") mustBe "newMessageAlert_SA309"
      emailTemplateFromMessageFormId("SA316_CY") mustBe "newMessageAlert_SA316"
      emailTemplateFromMessageFormId("SA300_CY") mustBe "newMessageAlert_SA300"
      emailTemplateFromMessageFormId("SS300_CY") mustBe "newMessageAlert_SS300"
      emailTemplateFromMessageFormId("P800 2032_CY") mustBe "newMessageAlert_P800_cy"
      emailTemplateFromMessageFormId("PA302 2032_CY") mustBe "newMessageAlert_PA302_cy"
    }

    "map all the SA not custom templates to `newMessageAlert_formId`" in {
      templatesToMapToNewMessageAlert.foreach { t =>
        val welshFormId = s"${t}_CY"
        emailTemplateFromMessageFormId(welshFormId) mustBe s"newMessageAlert_$t"
      }
    }

    "match on form ids with extra details message alerts" in {
      emailTemplateFromMessageFormId("SA309A July 2017_CY") mustBe "newMessageAlert_SA309"
      emailTemplateFromMessageFormId("SA316 (Batch 5)_CY") mustBe "newMessageAlert_SA316"
    }

    "use a standard template for other message alerts" in {
      emailTemplateFromMessageFormId("SAXXX_CY") mustBe "newMessageAlert_cy"
      emailTemplateFromMessageFormId("SAYYY_CY") mustBe "newMessageAlert_cy"
    }

    "not override the alert template only when alert template is different from newMessageAlert" in {
      emailTemplateFromMessageFormId("SA309A_CY", Some("myTemplateId")) mustBe "myTemplateId"
      emailTemplateFromMessageFormId("SA309A_CY", Some("newMessageAlert")) mustBe "newMessageAlert_SA309"
      emailTemplateFromMessageFormId("SA309A_CY") mustBe "newMessageAlert_SA309"
    }

    "map all the ITSA not custom templates to `new_message_alert_itsa_cy`" in {
      val itsaFormId = Map(
        "ITSAQU1"    -> "new_message_alert_itsaqu1",
        "ITSAQU2"    -> "new_message_alert_itsaqu2",
        "ITSAEOPS1"  -> "new_message_alert_itsaeops1",
        "ITSAEOPS2"  -> "new_message_alert_itsaeops2",
        "ITSAEOPSF"  -> "new_message_alert_itsaeopsf",
        "ITSAPOA1-1" -> "new_message_alert_itsapoa1-1",
        "ITSAPOA1-2" -> "new_message_alert_itsapoa1-2",
        "ITSAPOA2-1" -> "new_message_alert_itsapoa2-1",
        "ITSAPOA2-2" -> "new_message_alert_itsapoa2-2",
        "ITSAFD1"    -> "new_message_alert_itsafd1",
        "ITSAFD2"    -> "new_message_alert_itsafd2",
        "ITSAFD3"    -> "new_message_alert_itsafd3",
        "ITSAPOA-CN" -> "new_message_alert_itsapoa-cn",
        "ITSAUC1"    -> "new_message_alert_itsauc1"
      )
      itsaFormId.foreach { t =>
        val welshFormId = s"${t._1}_CY"
        emailTemplateFromMessageFormId(welshFormId) mustBe s"${t._2}_cy"
      }
    }
  }
}
