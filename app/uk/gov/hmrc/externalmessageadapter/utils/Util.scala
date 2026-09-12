/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.externalmessageadapter.utils

import java.util.{ Base64, UUID }

object Util {

  private val ACKNOWLEDGEMENT_REFERENCE_MAX_LENGTH_MINUS_ONE = 31
  private val LENGTH_32 = 32
  val HYPHEN = "-"
  val EMPTY_STRING = ""
  val COMMA_WITH_SPACE = ", "
  val COLON = ":"
  val THREE_COLONS = ":::"
  val ORIGIN_HIP_ERROR_MSG_PREFIX = "Origin:::HIP"

  def uuidOfLength31: String =
    UUID
      .randomUUID()
      .toString
      .replace(HYPHEN, EMPTY_STRING)
      .substring(0, ACKNOWLEDGEMENT_REFERENCE_MAX_LENGTH_MINUS_ONE)

  // Is being used for API-5951
  def uuidOfLength32: String =
    UUID.randomUUID().toString.replace(HYPHEN, EMPTY_STRING).substring(0, LENGTH_32)

  def uuidWithHyphenAndOfLength36: String = UUID.randomUUID().toString

  def encodeStringToBase64(inputString: String): String =
    Base64.getEncoder.encodeToString(inputString.getBytes("UTF-8"))
}
