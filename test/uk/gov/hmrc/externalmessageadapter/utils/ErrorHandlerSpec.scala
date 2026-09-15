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

import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.Status.INTERNAL_SERVER_ERROR
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{ DefaultAwaitTimeout, FakeRequest }

class ErrorHandlerSpec
    extends AnyWordSpec with DefaultAwaitTimeout with Matchers with GuiceOneAppPerSuite with OptionValues {

  private val errorHandler = new ErrorHandler()

  "ErrorHandlerSpec" should {

    "handle onClientError" in {
      val fakeRequest = FakeRequest()

      val result =
        errorHandler.onClientError(fakeRequest, BAD_REQUEST, "The message")

      status(result) mustBe BAD_REQUEST
      val expectedResponseBody =
        Json.obj(
          "status"    -> BAD_REQUEST,
          "failureId" -> "INVALID_PAYLOAD",
          "message"   -> "A client error occurred: The message"
        )
      contentAsJson(result) mustBe expectedResponseBody
    }

    "handle onServerError" in {
      val fakeRequest = FakeRequest()

      val result =
        errorHandler.onServerError(fakeRequest, new IllegalArgumentException("the error"))

      status(result) mustBe INTERNAL_SERVER_ERROR
      val expectedResponse =
        Json.obj(
          "status"    -> INTERNAL_SERVER_ERROR,
          "failureId" -> "SERVER_ERROR",
          "message"   -> "A server error occurred: the error"
        )
      contentAsJson(result) mustBe expectedResponse
    }

  }
}
