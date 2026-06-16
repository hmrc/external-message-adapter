/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

final case class HtmlParseException(private val message: String = "", private val cause: Throwable = None.orNull)
    extends Exception(message, cause)
