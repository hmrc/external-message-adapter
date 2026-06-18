/*
 * Copyright 2023 HM Revenue & Customs
 *
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
