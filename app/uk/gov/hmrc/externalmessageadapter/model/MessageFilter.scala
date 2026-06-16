/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import uk.gov.hmrc.common.message.model.Regime

case class MessageFilter(
  taxIdentifiers: Seq[String] = List(),
  regimes: Seq[Regime.Value] = List(),
  countOnly: Boolean = false
)
