/*
 * Copyright 2023 HM Revenue & Customs
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

import play.api.http.HeaderNames._
import play.api.http.HttpErrorHandler
import play.api.http.MimeTypes._
import play.api.mvc.Results._
import play.api.mvc._

import javax.inject.Singleton
import scala.concurrent._

@Singleton
class ErrorHandler extends HttpErrorHandler {

  def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    Future.successful(
      Status(statusCode)(
        s"""{ "status": $statusCode, "failureId": "INVALID_PAYLOAD", "message": "A client error occurred: $message" } """
      )
        .withHeaders(CONTENT_TYPE -> JSON)
    )

  def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    Future.successful(
      InternalServerError(
        s"""{ "status": 500, "failureId": "SERVER_ERROR", "message": "A server error occurred: ${exception.getMessage}" } """
      )
        .withHeaders(CONTENT_TYPE -> JSON)
    )

}
