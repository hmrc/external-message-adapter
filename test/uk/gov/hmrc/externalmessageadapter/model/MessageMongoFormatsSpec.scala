/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import java.time.{ Instant, LocalDate }
import org.mongodb.scala.bson.ObjectId
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.Json
import uk.gov.hmrc.domain.SaUtr
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures.updateIssueDate
import uk.gov.hmrc.externalmessageadapter.util.{ MessageFixtures, Resources }
import uk.gov.hmrc.common.message.model._

class MessageMongoFormatsSpec extends AnyWordSpecLike with Matchers {

  val taxPayername = TaxpayerName(
    title = Some("Dr"),
    forename = Some("Bruce"),
    secondForename = Some("Hulk"),
    surname = Some("Banner"),
    honours = Some("Green")
  )

  "The message mongo format" must {
    val exampleMessage = Message(
      id = new ObjectId("55a921d84f573b6f14325b57"),
      recipient = MessageFixtures.createTaxEntity(SaUtr("12345678")),
      subject = "asdfg",
      body = Some(Details(None, Some("tax-summary-notification"), None, None, issueDate = Some(LocalDate.now))),
      validFrom = LocalDate.parse("2015-07-17"),
      alertFrom = Some(LocalDate.parse("2015-07-18")),
      alertDetails = AlertDetails("templateId", Some(taxPayername), Map("key 1" -> "value 1", "key2" -> "value2")),
      alerts = Some(EmailAlert(None, Instant.parse("2015-07-17T15:40:08.829Z"), false, None)),
      rescindment =
        Some(Rescindment(Instant.parse("2015-07-17T15:40:08.829Z"), RescindmentType.GeneratedInError, "blah")),
      lastUpdated = None,
      hash = "O4KWyUPKQySWUVzQVfoPswBEKfN1gLe9dXi7EzCwp5U=",
      statutory = false,
      renderUrl = RenderUrl("sa-message-renderer", "/messages/sa/12345678/55a921d84f573b6f14325b57"),
      sourceData = None,
      tags = Some(Map("notificationType" -> "Direct Debit"))
    )

    "be able to read and write to the current mongo format" in {
      val currentJson = updateIssueDate(Resources.readJson("messages/mongo/allFieldsNoBreak.json"))

      Json.toJson(exampleMessage)(MessageMongoFormats.messageMongoFormat) must be(currentJson)

      currentJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(exampleMessage)
    }

    "be able to read and write to the current mongo format with verificationBreak" in {
      val currentJson = updateIssueDate(Resources.readJson("messages/mongo/verificationBreak.json"))

      val messageWithVerificationBrake = exampleMessage.copy(verificationBrake = Some(true))
      Json.toJson(messageWithVerificationBrake)(MessageMongoFormats.messageMongoFormat) must be(currentJson)

      currentJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(messageWithVerificationBrake)
    }

    "be able to read and write to the current mongo format without recipientName" in {
      val currentJson = updateIssueDate(Resources.readJson("messages/mongo/noRecipientName.json"))

      val messageWithoutRecipientName: Message =
        exampleMessage.copy(alertDetails = AlertDetails("newMessageAlert", None, Map()))
      Json.toJson(messageWithoutRecipientName)(MessageMongoFormats.messageMongoFormat) must be(currentJson)

      currentJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(messageWithoutRecipientName)
    }

    "be able to read the mongo format without the rescindment" in {
      val legacyJson = updateIssueDate(Resources.readJson("messages/mongo/legacy/noRescindment.json"))
      val messageNotRescinded = exampleMessage.copy(rescindment = None)

      Json.toJson(messageNotRescinded)(MessageMongoFormats.messageMongoFormat) must be(legacyJson)

      legacyJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(messageNotRescinded)
    }

    "be able to read the mongo format without the alertDetails" in {
      val legacyJson = updateIssueDate(Resources.readJson("messages/mongo/legacy/noAlertDetails.json"))
      val messageNotRescinded =
        exampleMessage.copy(alertDetails = AlertDetails("newMessageAlert", None, Map()), rescindment = None)

      legacyJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(messageNotRescinded)
    }

    "be able to read the mongo format without the statutory flag" in {
      val legacyJson = updateIssueDate(Resources.readJson("messages/mongo/legacy/noStatutoryFlag.json"))
      val messageStatutory = exampleMessage.copy(
        statutory = true,
        body = Some(
          Details(
            Some("SA316 2015"),
            Some("print-suppression-notification"),
            Some("2015-01-02"),
            Some("C0123456781234568")
          )
        )
      )
      legacyJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(messageStatutory)
    }

    "be able to read the render url if it is already stored in the database as a field" in {
      val legacyJson = updateIssueDate(Resources.readJson("messages/mongo/legacy/withRenderUrl.json"))
      val messageStatutory = exampleMessage.copy(
        statutory = true,
        body = Some(
          Details(
            Some("SA316 2015"),
            Some("print-suppression-notification"),
            Some("2015-01-02"),
            Some("C0123456781234568")
          )
        ),
        renderUrl = RenderUrl("abc", "url")
      )
      val m = legacyJson.as[Message](MessageMongoFormats.messageMongoFormat)
      m must be(messageStatutory)
    }

    "be able to construct the default the render url if it is not stored in the database as a field" in {
      val legacyJson = updateIssueDate(Resources.readJson("messages/mongo/legacy/noRenderUrl.json"))
      val messageStatutory = exampleMessage.copy(
        statutory = true,
        body = Some(
          Details(
            Some("SA316 2015"),
            Some("print-suppression-notification"),
            Some("2015-01-02"),
            Some("C0123456781234568")
          )
        ),
        renderUrl = RenderUrl("sa-message-renderer", "/messages/sa/12345678/55a921d84f573b6f14325b57")
      )
      legacyJson.as[Message](MessageMongoFormats.messageMongoFormat) must be(messageStatutory)
    }
  }
}
