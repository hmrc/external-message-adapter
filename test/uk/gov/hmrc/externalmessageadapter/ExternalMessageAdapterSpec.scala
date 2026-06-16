/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter

import java.time.Instant
import org.scalatestplus.play.PlaySpec

class ExternalMessageAdapterSpec extends PlaySpec {
  "ExternalMessageAdapterModule" must {
    val qm = new ExternalMessageAdapterModule
    "systemTimeSourceProvider create TimeSource with current date" in {
      val now = Instant.now
      qm.systemTimeSourceProvider().now().isAfter(now.minusSeconds(1)) mustBe true
    }
  }
}
