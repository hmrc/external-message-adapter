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

package uk.gov.hmrc.externalmessageadapter

import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Configuration
import play.api.inject.guice.GuiceApplicationBuilder

import java.time.Instant
import uk.gov.hmrc.externalmessageadapter.util.SpecBase

class ExternalMessageAdapterSpec extends SpecBase with GuiceOneAppPerSuite {

  "ExternalMessageAdapterModule" must {
    val externalMsgAdapterModule = new ExternalMessageAdapterModule

    "systemTimeSourceProvider create TimeSource with current date" in {
      val now = Instant.now
      externalMsgAdapterModule.systemTimeSourceProvider().now().isAfter(now.minusSeconds(1)) mustBe true
    }

    "return the bounceback formIds" in {
      val app = new GuiceApplicationBuilder()
        .configure(
          "auditing.enabled"                      -> "false",
          "microservice.metrics.graphite.enabled" -> "false",
          "metrics.enabled"                       -> "false"
        )
        .build()
      val config = app.injector.instanceOf[Configuration]

      externalMsgAdapterModule.bouncebackFormIds(config) mustBe Seq("CH(A)1700", "CH(A)1708")
    }
  }
}
