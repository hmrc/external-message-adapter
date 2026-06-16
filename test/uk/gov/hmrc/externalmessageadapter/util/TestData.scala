/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.util

import org.mongodb.scala.bson.ObjectId
import uk.gov.hmrc.common.message.model.TaxpayerName

import java.time.{ Instant, LocalDate, LocalDateTime, LocalTime }

object TestData {
  val TEST_ID = "test_id"
  val TEST_SUBJECT = "test_subject"
  val TEST_NAME = "test_name"
  val TEST_EMAIL_ADDRESS_VALUE = "test@test.com"
  val TEST_MESSAGE_TYPE = "test_msg_type"
  val TEST_COUNT = 5
  val TEST_REASON = "test_reason"
  val TEST_TITLE = "test_title"
  val TEST_TYPE = "test_type"
  val TEST_FORM = "test_form"
  val TEST_TIME_STRING = "2026-02-20T12:34:56Z"
  val TEST_SERVICE_NAME = "test_service"
  val TEST_URL = "http://localhost:9000/test_url"
  val TEST_SOURCE_DATA = "test_data"
  val TEST_CODE = "test_code"
  val TEST_IDENTIFIER = "EORINumber"
  val TEST_EVENT = "Event_hub"
  val TEST_BODY = "test_body"

  val TEST_KEY = "test_key"
  val TEST_KEY_VALUE = "test_key_value"

  val TEST_OBJECT_ID = new ObjectId("64c13ab08edf48a008793cac")

  val TEST_DAY = 20
  val TEST_MONTH = 2
  val TEST_YEAR = 2026
  val TEST_LOCAL_DATE: LocalDate = LocalDate.of(TEST_YEAR, TEST_MONTH, TEST_DAY)

  val TEST_EPOCH = 1234567L
  val TEST_TIME_INSTANT: Instant = Instant.ofEpochMilli(TEST_EPOCH)

  val TEST_MINUTES = 21
  val TEST_LOCAL_TIME: LocalTime = LocalTime.of(1, TEST_MINUTES)
  val TEST_LOCAL_DATE_TIME: LocalDateTime = LocalDateTime.of(TEST_LOCAL_DATE, TEST_LOCAL_TIME)

  val TEST_TAXPAYER: TaxpayerName = TaxpayerName(
    title = Some(TEST_TITLE),
    forename = Some(TEST_NAME)
  )
}
