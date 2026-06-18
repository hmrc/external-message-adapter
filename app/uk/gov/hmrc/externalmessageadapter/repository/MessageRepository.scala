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

import com.mongodb.client.model.Indexes.ascending
import org.bson.conversions.Bson
import java.time.Instant
import org.mongodb.scala.MongoException
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.model.{ Filters, IndexModel, IndexOptions, Updates }
import play.api.Logging
import uk.gov.hmrc.common.message.model
import uk.gov.hmrc.common.message.model.{ Delivered, LifecycleStatusType, Message, MessageMongoFormats }
import uk.gov.hmrc.domain.TaxIds.TaxIdWithName
import uk.gov.hmrc.externalmessageadapter.model.MessageFilter
import uk.gov.hmrc.externalmessageadapter.utils.TimeSource
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{ Codecs, PlayMongoRepository }
import org.mongodb.scala.{ ObservableFuture, SingleObservableFuture }

import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Named, Singleton }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

sealed trait MessageUpdateResult extends Product with Serializable

final case class Updated(summary: Message) extends MessageUpdateResult
final case class Unmodified(summary: Message) extends MessageUpdateResult
case object Missing extends MessageUpdateResult

trait MessageSelector {

  def taxIdRegimeSelector(
    authTaxIds: Set[TaxIdWithName]
  )(implicit messageFilter: MessageFilter): Option[Bson] = {

    val regimesJsonArr: Option[Seq[Bson]] = Option(messageFilter.regimes)
      .filter(_.nonEmpty)
      .map(
        _.map((regime: model.Regime.Value) => Filters.equal("recipient.regime", Codecs.toBson(regime)))
          .foldLeft(Seq.empty[Bson])((acc, e) => acc.+:(e))
      )

    val taxIdNames: Seq[String] =
      if (messageFilter.taxIdentifiers.isEmpty && messageFilter.regimes.isEmpty) {
        authTaxIds.map(_.name).toSeq
      } else {
        messageFilter.taxIdentifiers
      }

    authTaxIds
      .flatMap(authTaxId =>
        if (taxIdNames.contains(authTaxId.name)) {
          Seq(
            Filters.and(
              Filters.equal("recipient.identifier.name", authTaxId.name),
              Filters.equal("recipient.identifier.value", authTaxId.value)
            )
          )
        } else {
          regimesJsonArr.fold(Seq[Bson]())(ar =>
            Seq[Bson](
              Filters.and(
                Filters.equal("recipient.identifier.value", authTaxId.value),
                Filters.equal("recipient.identifier.name", authTaxId.name),
                Filters.or(ar*)
              )
            )
          )
        }
      )
      .foldLeft[Option[List[Bson]]](None) {
        case (None, e)    => Some(List(e))
        case (Some(a), e) => Some(a.+:(e))
      }
      .map(x => Filters.or(x*))

  }
}

@Singleton
class MongoMessageRepository @Inject() (
  mongo: MongoComponent,
  val timeSource: TimeSource,
  @Named("query-max-time-ms") queryMaxTimeMs: Long,
  @Named("ttl-duration") expiry: Duration
)(implicit executionContext: ExecutionContext)
    extends MessageSelector with Logging {

  import uk.gov.hmrc.common.message.model.MessageMongoFormats._

  private final val DuplicateKey = 11000

  protected[repository] lazy val repo =
    new PlayMongoRepository[Message](
      mongo,
      "quadient.message",
      messageMongoFormat,
      Seq(
        IndexModel(ascending("hash"), IndexOptions().name("unique-messageHash").unique(true)),
        IndexModel(
          ascending("externalRef.id", "externalRef.source"),
          IndexOptions().name("unique-externalRef").unique(true).sparse(true).background(true)
        ),
        IndexModel(ascending("alertFrom"), IndexOptions().unique(false)),
        IndexModel(ascending("status"), IndexOptions().name("status").unique(false)),
        IndexModel(
          ascending("recipient.identifier.value", "recipient.identifier.name"),
          IndexOptions()
            .name("recipient-tax-id-v2")
            .unique(false)
            .background(true)
        ),
        IndexModel(
          ascending("recipient.regime"),
          IndexOptions().name("recipient-regime-id-v2").unique(false).background(true)
        ),
        IndexModel(ascending("body.threadId"), IndexOptions().unique(false).background(true)),
        IndexModel(ascending("body.envelopId"), IndexOptions().unique(false).background(true)),
        IndexModel(ascending("mailgunStatus", "deliveredOn"), IndexOptions().unique(false).background(true)),
        IndexModel(
          ascending("deliveredOn"),
          IndexOptions()
            .name("deliveredOnIndex")
            .unique(false)
            .sparse(false)
            .background(true)
            .expireAfter(expiry.toSeconds, TimeUnit.SECONDS)
        )
      ),
      extraCodecs = Seq(
        Codecs.playFormatCodec(MongoJavatimeFormats.instantFormat),
        Codecs.playFormatCodec(MongoJavatimeFormats.localDateFormat),
        Codecs.playFormatCodec(MessageMongoFormats.LocalDateFormatter.localDateFormat),
        Codecs.playFormatCodec(LifecycleStatusType.format)
      ),
      replaceIndexes = true
    )

  def findBy(authTaxIds: Set[TaxIdWithName], withRescinded: Boolean = false)(implicit
    messageFilter: MessageFilter
  ): Future[Seq[Message]] =
    taxIdRegimeSelector(authTaxIds)
      .map(
        Filters.and(_, readyForViewingQuery, if (!withRescinded) rescindedExcludedQuery else Filters.empty())
      )
      .fold(Future.successful(Seq[Message]()))(query =>
        repo.collection
          .find(query)
          .maxTime(Duration(queryMaxTimeMs, TimeUnit.MILLISECONDS))
          .sort(Filters.equal("_id", -1))
          .toFuture()
      )

  def insertIfUnique(message: Message): Future[Boolean] =
    repo.collection
      .insertOne(
        message
      )
      .toFuture()
      .map(_ => true)
      .recoverWith {
        case e: MongoException if e.getCode == DuplicateKey =>
          logger.warn(s"Ignoring duplicate found on insertion to Message collection:  ${e.getMessage}")
          Future.successful(false)
      }

  def findById(id: ObjectId, withRescinded: Boolean = false): Future[Option[Message]] = {
    val filter = Filters.and(
      Filters.equal("_id", id),
      readyForViewingQuery,
      if (!withRescinded) rescindedExcludedQuery else Filters.empty()
    )
    repo.collection
      .find(
        filter
      )
      .maxTime(Duration(queryMaxTimeMs, TimeUnit.MILLISECONDS))
      .headOption()
  }.recover { case e =>
    logger.error(e.getMessage)
    None
  }

  def findByExternalRefId(externalRefId: String): Future[Option[Message]] = {
    val filter = Filters.and(
      Filters.equal("externalRef.id", externalRefId),
      readyForViewingQuery,
      rescindedExcludedQuery
    )
    repo.collection.find(filter).maxTime(Duration(queryMaxTimeMs, TimeUnit.MILLISECONDS)).headOption()
  }

  def save(message: Message): Future[Boolean] =
    repo.collection.insertOne(message).toFuture().map(_.wasAcknowledged())

  def removeById(id: ObjectId): Future[Boolean] =
    repo.collection.deleteOne(Filters.equal("_id", id)).toFuture().map(_.wasAcknowledged())

  protected def readyForViewingQuery: Bson =
    Filters.and(Filters.lte("validFrom", timeSource.today()), Filters.nor(Filters.equal("verificationBrake", true)))

  def rescindedExcludedQuery: Bson = Filters.eq("rescindment", null) // scalastyle:ignore

  def findDeliveredRecords(count: Int, date: Instant): Future[Seq[Message]] =
    repo.collection.find(deliveredMessagesQuery(date)).batchSize(count).toFuture()

  private def deliveredMessagesQuery(date: Instant) =
    Filters.and(
      Filters.equal("mailgunStatus", Delivered.name),
      Filters.lt("deliveredOn", date)
    )

  def processDeliveredEvent(externalRefId: String, deliveredOn: Instant): Future[Unit] = {
    val statusUpdate =
      Updates.combine(
        Updates.set("mailgunStatus", Delivered.name),
        Updates.set("deliveredOn", deliveredOn)
      )
    repo.collection
      .findOneAndUpdate(
        Filters.equal("externalRef.id", externalRefId),
        statusUpdate
      )
      .toFuture()
      .map(_ => ())
  }

}
