/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import java.time.LocalDate
import org.scalatestplus.play.PlaySpec
import uk.gov.hmrc.common.message.model.formatDate

class FormatDateSpec extends PlaySpec {

  "The formatDate method" must {

    "format a date in yyyy-MM-dd format" in {
      formatDate(LocalDate.of(1111, 11, 11)) mustBe "1111-11-11"
      formatDate(LocalDate.of(1111, 1, 1)) mustBe "1111-01-01"
      formatDate(LocalDate.of(2015, 6, 23)) mustBe "2015-06-23"
    }
  }
}
