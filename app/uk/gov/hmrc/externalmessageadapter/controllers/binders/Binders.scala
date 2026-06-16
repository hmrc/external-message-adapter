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

package uk.gov.hmrc.externalmessageadapter.controllers.binders

import org.mongodb.scala.bson.ObjectId
import play.api.mvc.PathBindable

import scala.util.{ Failure, Success, Try }

object Binders {

  implicit def objectIdBinder(implicit stringBinder: PathBindable[String]): PathBindable[ObjectId] =
    new PathBindable[ObjectId] {
      def bind(key: String, value: String): Either[String, ObjectId] = stringBinder.bind(key, value) match {
        case Left(msg) => Left(msg)
        case Right(id) =>
          Try(new ObjectId(id)) match {
            case Success(oid) => Right(oid)
            case Failure(_)   => Left(s"ID $id was invalid")
          }
      }

      def unbind(key: String, value: ObjectId): String = value.toString
    }

}
