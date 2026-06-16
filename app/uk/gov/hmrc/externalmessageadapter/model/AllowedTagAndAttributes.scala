/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

case class AllowedTagAndAttributes(
  tag: String,
  attributes: List[String] = List()
)
