/*
 * Copyright 2023 HM Revenue & Customs
 *
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
