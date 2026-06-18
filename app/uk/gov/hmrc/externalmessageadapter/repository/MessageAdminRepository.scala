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

package uk.gov.hmrc.externalmessageadapter.repository

import org.mongodb.scala.model.{ Filters, IndexModel }
import uk.gov.hmrc.common.message.model.MessageMongoFormats._
import uk.gov.hmrc.externalmessageadapter.utils.TimeSource
import uk.gov.hmrc.common.message.model.Message
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import org.mongodb.scala.SingleObservableFuture

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class MessageAdminRepository @Inject() (
  mongo: MongoComponent,
  val timeSource: TimeSource
)(implicit executionContext: ExecutionContext)
    extends PlayMongoRepository[Message](
      mongo,
      "quadient.message",
      messageMongoFormat,
      Seq.empty[IndexModel]
    ) with MessageSelector {
  def deleteAll(): Future[Int] =
    collection.deleteMany(Filters.empty()).toFuture().map(_.getDeletedCount.toInt)
}
