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
