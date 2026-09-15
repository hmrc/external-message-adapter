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
