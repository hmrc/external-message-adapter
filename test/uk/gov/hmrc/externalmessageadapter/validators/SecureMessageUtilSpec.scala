/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.validators

import org.bson.types.ObjectId

import java.time.LocalDate
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.{ JsResultException, JsValue, Json }
import uk.gov.hmrc.common.message.model.{ AlertDetails, Message, Regime, RenderUrl, TaxEntity }
import uk.gov.hmrc.domain.HmrcMtdVat
import uk.gov.hmrc.externalmessageadapter.util.Resources
import uk.gov.hmrc.externalmessageadapter.util.TestData.{ TEST_BODY, TEST_SUBJECT }
import uk.gov.hmrc.externalmessageadapter.validators.SecureMessageUtilSpec.*
import uk.gov.hmrc.externalmessageadapter.validators.SecureMessageUtil.Content

class SecureMessageUtilSpec extends AnyWordSpecLike with MockitoSugar with Matchers with ScalaFutures {

  "checkAndValidateContent" must {
    val message = Message(
      id = new ObjectId,
      recipient = TaxEntity(Regime.sa, HmrcMtdVat("mtd-vat"), Some("test@test.com")),
      subject = "RE: Subject",
      body = None,
      validFrom = LocalDate.now(),
      alertFrom = None,
      alertDetails = AlertDetails("template-id", None, Map()),
      lastUpdated = None,
      hash = "*hash*",
      statutory = true,
      renderUrl = RenderUrl(service = "my-service", url = "service-url"),
      sourceData = None,
      externalRef = None,
      content = None
    )

    "return Success - when there is no content present" in {
      SecureMessageUtil.checkValidContent(message).isSuccess mustBe true
    }

    "return Success for legacy content" in {
      SecureMessageUtil.checkValidContent(message.copy(content = Some(legacyContent))).isSuccess mustBe true
    }

    "return Success for valid new content" in {
      SecureMessageUtil.checkValidContent(message.copy(content = Some(validContentWithSection))).isSuccess mustBe true
    }

    "return failure for the new content having missing information" in {
      SecureMessageUtil
        .checkValidContent(message.copy(content = Some(inValidContentWithMissingInfo)))
        .isFailure mustBe true
    }

  }

  "createSecureMessage" must {
    "Successfully creates a secure message for the given legacy v3 message" in {
      SecureMessageUtil.createSecureMessage(newMessage) mustBe newMessageV4
    }

    "Successfully creates a secure message for the v3 message having new content" in {
      SecureMessageUtil.createSecureMessage(newMessage_withNewContent) mustBe newMessageV4_withNewContent
    }

    "Successfully creates a secure message for the v3 message having new content not including subject" in {
      SecureMessageUtil.createSecureMessage(
        newMessage_withNewContentWithoutSubject
      ) mustBe newMessageV4_withNewContentWithoutSubject
    }

    "Successfully creates a secure message for the v3 message having the identifier as 'HMRC-MTD-VAT.VRN' " in {
      SecureMessageUtil.createSecureMessage(newMessage_withVRN) mustBe newMessageV4_withVRN
    }
  }

  "Content.contentFormat" must {
    import Content.contentFormat

    "read the json correctly" in {
      Json.parse(contentJsonString).as[Content] mustBe content
    }

    "throw exception for invalid json" in {
      intercept[JsResultException] {
        Json.parse(contentInvalidJsonString).as[Content]
      }
    }

    "write the object correctly" in {
      Json.toJson(content) mustBe Json.parse(contentJsonString)
    }
  }
}

object SecureMessageUtilSpec {
  val legacyContent: String =
    """
      |<p>You need to file a Self Assessment tax return for the 2021 to 2022 tax year if you haven't already. The tax year ended on 5 April 2022.</p>
      |<p>You must file your online return by 31 January 2023.</p>
      |<p>If you've already completed your tax return for the 2021 to 2022 tax year, or we've told you that you don't need to send us a 2021 to 2022 tax return, you don't need to do anything else. </p>
      |<p>You can <a href=\"https://www.gov.uk/pay-self-assessment-tax-bill/through-your-tax-code\">pay through your Pay As You Earn tax code</a> if you owe less than £3,000 (you'll need to file by 30 December 2022).</p>
      |<p>By law, you need to complete your tax return, even if you've already paid all the tax you owe, or you think you don't owe any tax. You can check if you need to send a tax return using our <a href=\"https://www.gov.uk/check-if-you-need-tax-return\">online tool</a>.</p>
      |<h3>Start your return now</h3>
      |<p>The sooner you get started the better ‐ it can take time to gather all the information you need. Start <a href=\"https://www.gov.uk/log-in-file-self-assessment-tax-return\">filling in your return online</a>.</p>
      |<p>You'll have to buy <a href=\"https://www.gov.uk/government/publications/self-assessment-commercial-software-suppliers\">commercial software</a> if you need to send a partnership or trust and estate tax return online, or if you're a minister of religion or lived abroad as a non-resident.</p>
      |""".stripMargin

  val validContentWithSection: String =
    """<section lang="en" subject="Test Subject">
      |    <p>You need to file a Self Assessment tax return for the 2021 to 2022 tax year if you haven't already. The tax year ended on 5 April 2022.</p>
      |    <p>You must file your online return by 31 January 2023.</p>
      |    <p>If you've already completed your tax return for the 2021 to 2022 tax year, or we've told you that you don't need to send us a 2021 to 2022 tax return, you don't need to do anything else.
      |    </p>
      |    <p>You can <a href=\"https://www.gov.uk/pay-self-assessment-tax-bill/through-your-tax-code\">pay through your Pay As You Earn tax code</a> if you owe less than £3,000 (you'll need to file by
      |    30 December 2022).</p>
      |    <p>By law, you need to complete your tax return, even if you've already paid all the tax you owe, or you think you don't owe any tax. You can check if you need to send a tax return using our
      |    <a href=\"https://www.gov.uk/check-if-you-need-tax-return\">online tool</a>.</p>
      |    <h3>Start your return now</h3>
      |    <p>The sooner you get started the better ‐ it can take time to gather all the information you need. Start <a href=\"https://www.gov.uk/log-in-file-self-assessment-tax-return\">filling in
      |    your return online</a>.</p>
      |    <p>You'll have to buy <a href=\"https://www.gov.uk/government/publications/self-assessment-commercial-software-suppliers\">commercial software</a> if you need to send a partnership or trust
      |    and estate tax return online, or if you're a minister of religion or lived abroad as a non-resident.</p>
      |</section>""".stripMargin

  val inValidContentWithMissingInfo: String =
    """<section>
      |    <p>You need to file a Self Assessment tax return for the 2021 to 2022 tax year if you haven't already. The tax year ended on 5 April 2022.</p>
      |    <p>You must file your online return by 31 January 2023.</p>
      |    <p>If you've already completed your tax return for the 2021 to 2022 tax year, or we've told you that you don't need to send us a 2021 to 2022 tax return, you don't need to do anything else.
      |    </p>
      |    <p>You can <a href=\"https://www.gov.uk/pay-self-assessment-tax-bill/through-your-tax-code\">pay through your Pay As You Earn tax code</a> if you owe less than £3,000 (you'll need to file by
      |    30 December 2022).</p>
      |    <p>By law, you need to complete your tax return, even if you've already paid all the tax you owe, or you think you don't owe any tax. You can check if you need to send a tax return using our
      |    <a href=\"https://www.gov.uk/check-if-you-need-tax-return\">online tool</a>.</p>
      |    <h3>Start your return now</h3>
      |    <p>The sooner you get started the better ‐ it can take time to gather all the information you need. Start <a href=\"https://www.gov.uk/log-in-file-self-assessment-tax-return\">filling in
      |    your return online</a>.</p>
      |    <p>You'll have to buy <a href=\"https://www.gov.uk/government/publications/self-assessment-commercial-software-suppliers\">commercial software</a> if you need to send a partnership or trust
      |    and estate tax return online, or if you're a minister of religion or lived abroad as a non-resident.</p>
      |</section>""".stripMargin

  val newMessage: JsValue = Resources.readJson("messages/controller/v3/GMC_With_Full_Details.json")
  val newMessageV4: JsValue = Resources.readJson("messages/controller/v4/GMC_With_Full_Details.json")

  val newMessage_withNewContent: JsValue = Resources.readJson("messages/controller/v3/GMC_New_Content.json")
  val newMessageV4_withNewContent: JsValue = Resources.readJson("messages/controller/v4/GMC_New_Content.json")

  val newMessage_withNewContentWithoutSubject: JsValue =
    Resources.readJson("messages/controller/v3/GMC_New_Content_without_subject.json")
  val newMessageV4_withNewContentWithoutSubject: JsValue =
    Resources.readJson("messages/controller/v4/GMC_New_Content_without_subject.json")

  val newMessage_withVRN: JsValue = Resources.readJson("messages/controller/v3/GMC_VRN.json")
  val newMessageV4_withVRN: JsValue = Resources.readJson("messages/controller/v4/GMC_VRN.json")

  val content: Content = Content(lang = "English", subject = TEST_SUBJECT, body = TEST_BODY)

  val contentJsonString: String = """{"lang":"English","subject":"test_subject","body":"test_body"}""".stripMargin

  val contentInvalidJsonString: String =
    """{"subject":"test_subject","body":"test_body"}""".stripMargin
}
