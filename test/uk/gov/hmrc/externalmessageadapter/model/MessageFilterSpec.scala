/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import uk.gov.hmrc.common.message.model.Regime
import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.util.TestData.TEST_IDENTIFIER

class MessageFilterSpec extends SpecBase {

  "taxIdentifiers" should {
    "return correct value" when {
      "taxIdentifiers are empty" in {
        MessageFilter().taxIdentifiers mustBe empty
      }

      "taxIdentifiers have some value" in {
        MessageFilter(taxIdentifiers = Seq(TEST_IDENTIFIER)).taxIdentifiers mustBe Seq(TEST_IDENTIFIER)
      }
    }
  }

  "regimes" should {
    "return correct value" when {
      "regimes are empty" in {
        MessageFilter().regimes mustBe empty
      }

      "regimes have some value" in {
        MessageFilter(regimes = Seq(Regime.vat)).regimes mustBe Seq(Regime.vat)
      }
    }
  }

  "countOnly" should {
    "return correct value" in {
      MessageFilter(countOnly = true).countOnly mustBe true
    }
  }
}
