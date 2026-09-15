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

import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.utils.Util.*

class UtilSpec extends SpecBase {

  "constants" should {
    "return the correct values" in {
      HYPHEN mustBe "-"
      EMPTY_STRING mustBe ""
      COMMA_WITH_SPACE mustBe ", "
      COLON mustBe ":"
      THREE_COLONS mustBe ":::"
    }
  }

  "encodeStringToBase64" should {
    "return the encoded value" in {
      encodeStringToBase64("AbCdEf123456:AbCdEf123897") mustBe "QWJDZEVmMTIzNDU2OkFiQ2RFZjEyMzg5Nw=="
    }
  }

  "uuidOfProvidedLength" should {

    "return the UUID string of length 31 and without any hyphen" in {
      val resultedUUID = uuidOfProvidedLength(ACKNOWLEDGEMENT_REFERENCE_MAX_LENGTH_MINUS_ONE)

      resultedUUID.length mustBe 31
      assert(!resultedUUID.contains(HYPHEN))
    }

    "return the UUID string of length 32 and without any hyphen" in {
      val resultedUUID = uuidOfProvidedLength(LENGTH_32)

      resultedUUID.length mustBe 32
      assert(!resultedUUID.contains(HYPHEN))
    }

    "return UUID of 36 characters and with hyphen" in {
      val resultedUUID = uuidOfProvidedLength(LENGTH_36)

      resultedUUID.length mustBe 36
      resultedUUID.contains(HYPHEN) mustBe true
    }

    "return UUID of default length and with hyphen when no length is provided" in {
      val resultedUUID = uuidOfProvidedLength()

      resultedUUID.contains(HYPHEN) mustBe true
    }
  }
}
