/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers

import org.apache.commons.codec.binary.Base64
import java.time.{ Instant, LocalDate }
import org.mongodb.scala.bson.ObjectId
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.common.message.model._

import java.security.MessageDigest

object MessageRESTFormatsV4 extends AlertEmailTemplateMapper {

  implicit val messageApiV4Reads: Reads[Message] =
    ((__ \ "externalRef").read[ExternalRef] and
      (__ \ "recipient").read[Recipient] and
      (__ \ "messageType").read[String] and
      (__ \ "subject").readNullable[String] and
      (__ \ "validFrom").readNullable[LocalDate](localDateReads) and
      (__ \ "deliveredOn").readNullable[Instant] and
      (__ \ "content").read[String] and
      (__ \ "details").readNullable[MessageDetails] and
      (__ \ "alertQueue").readNullable[String] and
      (__ \ "emailAlertEventUrl").readNullable[String] and
      Reads[Option[Map[String, String]]](jsValue =>
        (__ \ "alertDetails" \ "data").asSingleJson(jsValue) match {
          case JsDefined(value) =>
            value
              .validate[Map[String, String]]
              .map(Some.apply)
              .orElse(JsError("sourceData: invalid source data provided"))
          case _: JsUndefined => JsSuccess(None)
        }
      ) and
      Reads[Option[Map[String, String]]](jsValue =>
        (__ \ "tags").asSingleJson(jsValue) match {
          case JsDefined(value) =>
            value
              .validate[Map[String, String]]
              .map(Some.apply)
              .orElse(JsError("tags : invalid data provided"))
          case _: JsUndefined => JsSuccess(None)
        }
      )) {
      (
        externalRef,
        recipient,
        messageType,
        subjectOpt,
        vf,
        deliveredOn,
        content,
        messageDetails,
        alertQueue,
        emailAlertEventUrl,
        alertDetailsData,
        tags
      ) =>
        val issueDate = messageDetails.flatMap(_.issueDate).getOrElse(LocalDate.now)

        val validFrom = vf.filter(_.isAfter(issueDate)).getOrElse(issueDate)

        val id = new ObjectId

        def decodeBase64String(input: String): String =
          new String(Base64.decodeBase64(input.getBytes("UTF-8")))

        val subject = subjectOpt.getOrElse("")
        val hash: String = {
          val sha256Digester = MessageDigest.getInstance("SHA-256")
          Base64.encodeBase64String(
            sha256Digester.digest(
              Seq(
                subject,
                content,
                messageDetails.map(_.formId).getOrElse(""),
                recipient.taxIdentifier.name,
                recipient.taxIdentifier.value,
                validFrom.toString
              ).mkString("/").getBytes("UTF-8")
            )
          )
        }

        val email = recipient.email.fold[Map[String, String]](Map.empty)(v => Map("email" -> v))
        val responseTime: Map[String, String] =
          messageDetails.flatMap(_.waitTime).fold[Map[String, String]](Map.empty)(v => Map("waitTime" -> v))
        val subjectData = if (subject.isEmpty) None else Some(Map("subject" -> subject))
        val data = email ++ responseTime ++ Map("date" -> validFrom.toString) ++ subjectData.getOrElse(
          Map()
        ) ++ alertDetailsData.getOrElse(Map())

        val rendererService = "external-message-adapter"

        val details = messageDetails.map { ds =>
          val threadId = ds.threadId.getOrElse(new ObjectId().toString) // DC-1738

          Details(
            Some(ds.formId),
            Some(messageType),
            None,
            None,
            Some(ds.paperSent),
            ds.batchId,
            Some(issueDate),
            ds.replyTo,
            Some(threadId),
            ds.enquiryType,
            ds.adviser,
            ds.waitTime,
            ds.topic,
            properties = ds.properties
          )
        }

        val templateId = messageDetails
          .map(_.formId)
          .map(emailTemplateFromMessageFormId(_))
          .getOrElse(messageType)

        val recipientName = recipient.name.map(_.withDefaultLine1)

        val url = s"/external-message-adapter/external/messages/${id.toString}/content"
        Message(
          id = id,
          recipient = TaxEntity.create(recipient.taxIdentifier, recipient.email, recipient.regime),
          subject = subject,
          body = details,
          validFrom = validFrom,
          lastUpdated = None,
          alertFrom = Some(validFrom),
          alertDetails = AlertDetails(templateId, recipientName, data),
          alertQueue = alertQueue,
          hash = hash,
          statutory = messageDetails.exists(_.statutory),
          renderUrl = RenderUrl(rendererService, url),
          externalRef = Some(externalRef),
          content = Some(decodeBase64String(content)),
          sourceData = messageDetails.flatMap(_.sourceData),
          emailAlertEventUrl = emailAlertEventUrl,
          tags = tags,
          deliveredOn = deliveredOn
        )
    }

}
