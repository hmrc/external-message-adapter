/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import play.utils.UriEncoding

trait UriValueEncoder {
  def encode(value: String) = UriEncoding.encodePathSegment(value, "UTF-8")
}
