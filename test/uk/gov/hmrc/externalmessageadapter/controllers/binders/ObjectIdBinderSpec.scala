/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.controllers.binders

import org.mongodb.scala.bson.ObjectId
import org.scalatestplus.play.PlaySpec
import play.api.mvc.PathBindable.Parsing
import uk.gov.hmrc.externalmessageadapter.controllers.binders.Binders.objectIdBinder

class ObjectIdBinderSpec extends PlaySpec {

  "objectIdBinder" must {
    val objectIdAsString = "53fc7104010000010071a4f2"
    val bsonId = new ObjectId(objectIdAsString)

    "binds a string to a BSONObjectId" in {
      objectIdBinder.bind("id", objectIdAsString) must be(Right(bsonId))
    }

    "does not binds a string when the string binder fails" in {
      implicit object alwaysFailBindableString
          extends Parsing[String](
            _ => throw new RuntimeException("always fail"),
            _ => throw new RuntimeException("always fail"),
            (s, e) => s"Cannot parse parameter $s as String: ${e.getMessage}"
          )
      objectIdBinder.bind("id", objectIdAsString) must be(a[Left[_, _]])
    }

    "does not bind a string to a BSONObjectId if is not valid" in {
      objectIdBinder.bind("id", "this is not a BSON id") must be(a[Left[_, _]])
    }

    "unbind a BSONObjectId" in {
      objectIdBinder.unbind("id", bsonId) must be(objectIdAsString)
    }
  }

}
