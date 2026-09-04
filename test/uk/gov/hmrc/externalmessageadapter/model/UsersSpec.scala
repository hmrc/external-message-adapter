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

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.externalmessageadapter.util.SpecBase
import uk.gov.hmrc.externalmessageadapter.util.TestData.TEST_ID

class UsersSpec extends SpecBase {

  "Json reads" should {
    import Users.reads

    "read the json correctly" in new Setup {
      Json.parse(usersJsonString).as[Users] mustBe users
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(usersInvalidJsonString).as[Users]
      }
    }

    trait Setup {
      val users: Users = Users(principalUserIds = List(TEST_ID), delegatedUserIds = List(TEST_ID))

      val usersJsonString = """{"principalUserIds":["test_id"], "delegatedUserIds":["test_id"]}"""
      val usersInvalidJsonString = """{"principalUserIds":"test_id"}"""
    }
  }
}
