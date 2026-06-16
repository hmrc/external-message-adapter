/*
 * Copyright 2026 HM Revenue & Customs
 *
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
