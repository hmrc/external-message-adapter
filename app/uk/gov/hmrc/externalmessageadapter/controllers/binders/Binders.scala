/*
 * Copyright 2023 HM Revenue & Customs
 *
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
