/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import java.time.LocalDate
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import uk.gov.hmrc.externalmessageadapter.controllers.MessageRESTFormatsV4

class MessageRESTFormatsV4Spec extends PlaySpec {

  "must always have alertFrom even when validFrom is not provided" in {
    val parsedMessage = MessageRESTFormatsV4.messageApiV4Reads.reads(Json.parse(messageWithoutAlertFrom))
    parsedMessage.get.alertFrom mustBe Some(LocalDate.now())
  }

  "must serialise the JSon into message even missing subject" in {
    val parsedMessage = MessageRESTFormatsV4.messageApiV4Reads.reads(Json.parse(messageWithoutSubject))
    parsedMessage.get.subject mustBe ""
  }

  "alertFrom must be the same as validFrom when valid from is provided" in {
    val parsedMessage = MessageRESTFormatsV4.messageApiV4Reads.reads(Json.parse(messageWithAlertFrom))
    parsedMessage.get.alertFrom must not be None
    parsedMessage.get.alertFrom mustBe Some(parsedMessage.get.validFrom)
  }

  "check the renderer is set to 'external-message-adapter'" in {
    val parsedMessage = MessageRESTFormatsV4.messageApiV4Reads.reads(Json.parse(messageWithoutAlertFrom))
    parsedMessage.get.renderUrl.service mustEqual "external-message-adapter"
  }

  "check tags are serialised into their map counterpart" in {
    val parsedMessage = MessageRESTFormatsV4.messageApiV4Reads.reads(Json.parse(messageWithTags))
    parsedMessage.get.tags.getOrElse(Map()).getOrElse("notificationType", "") mustEqual "Direct Debit"
  }

  val messageWithTags = """{
                          |   "externalRef":{
                          |       "id":"666",
                          |       "source":"mdtp"
                          |   },
                          |   "recipient":{
                          |       "taxIdentifier":{
                          |           "name":"HMRC-CUS-ORG",
                          |           "value":"GB1234567890"
                          |       },
                          |       "name":{
                          |           "line1": "Mr. John Smith"
                          |       },
                          |       "email":"johnsmith@gmail.com"
                          |   },
                          |   "validFrom": "2018-01-01",
                          |   "messageType":"cds_ddi_setup_dcs_alert",
                          |   "validFrom": "2020-05-04",
                          |   "subject":"Confirmation of your CDS Registration",
                          |   "content":"SGVsbG8gV29ybGQ=",
                          |   "alertQueue":"DEFAULT",
                          |    "tags": {
                          |      "notificationType": "Direct Debit"
                          |    }
       }""".stripMargin

  val messageWithoutAlertFrom = """{
                                  |   "externalRef":{
                                  |       "id":"666",
                                  |       "source":"sees"
                                  |   },
                                  |   "recipient":{
                                  |       "taxIdentifier":{
                                  |           "name":"HMRC-OBTDS-ORG",
                                  |           "value":"XZFH00000100024"
                                  |       },
                                  |       "name":{
                                  |           "line1": "Mr. John Smith"
                                  |       },
                                  |       "email":"johnsmith@gmail.com"
                                  |   },
                                  |   "messageType":"fhddsAlertMessage",
                                  |   "subject":"Confirmation of your FHDDS Registration",
                                  |   "content":"SGVsbG8gV29ybGQ=",
                                  |   "alertQueue":"PRIORITY"
       }""".stripMargin

  val messageWithAlertFrom = """{
                               |   "externalRef":{
                               |       "id":"666",
                               |       "source":"sees"
                               |   },
                               |   "recipient":{
                               |       "taxIdentifier":{
                               |           "name":"HMRC-OBTDS-ORG",
                               |           "value":"XZFH00000100024"
                               |       },
                               |       "name":{
                               |           "line1": "Mr. John Smith"
                               |       },
                               |       "email":"johnsmith@gmail.com"
                               |   },
                               |   "validFrom": "2018-01-01",
                               |   "messageType":"fhddsAlertMessage",
                               |   "subject":"Confirmation of your FHDDS Registration",
                               |   "content":"SGVsbG8gV29ybGQ=",
                               |   "alertQueue":"PRIORITY"
       }""".stripMargin

  val messageWithoutSubject = """{
                                |   "externalRef":{
                                |       "id":"666",
                                |       "source":"sees"
                                |   },
                                |   "recipient":{
                                |       "taxIdentifier":{
                                |           "name":"HMRC-OBTDS-ORG",
                                |           "value":"XZFH00000100024"
                                |       },
                                |       "name":{
                                |           "line1": "Mr. John Smith"
                                |       },
                                |       "email":"johnsmith@gmail.com"
                                |   },
                                |   "messageType":"fhddsAlertMessage",
                                |   "content":"SGVsbG8gV29ybGQ=",
                                |   "alertQueue":"PRIORITY"
       }""".stripMargin
}
