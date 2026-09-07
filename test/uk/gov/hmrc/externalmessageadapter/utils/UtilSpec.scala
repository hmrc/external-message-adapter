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
import uk.gov.hmrc.externalmessageadapter.utils.Util.HYPHEN

class UtilSpec extends SpecBase {

  "uuidOfLength31" should {

    "return the UUID string of length 31 and without any hyphen" in {
      Util.uuidOfLength31.length mustBe 31
      assert(!Util.uuidOfLength31.contains(HYPHEN))
    }
  }

  "uuidOfLength32" should {
    "return the UUID string of length 32 and without any hyphen" in {
      Util.uuidOfLength32.length mustBe 32
      assert(!Util.uuidOfLength32.contains(HYPHEN))
    }
  }
}
