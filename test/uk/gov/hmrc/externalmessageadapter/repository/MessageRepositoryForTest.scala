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

package uk.gov.hmrc.externalmessageadapter.repository

import java.time.Instant
import org.scalatestplus.play.PlaySpec
import play.api.inject.{ Injector, bind }
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.externalmessageadapter.MetricOrchestratorStub
import uk.gov.hmrc.externalmessageadapter.utils.{ SystemTimeSource, TimeSource }
import uk.gov.hmrc.common.message.model.Message
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.lock.{ LockRepository, MongoLockRepository }
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.test.MongoSupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait MessageRepositoryForTest extends MetricOrchestratorStub with MongoSupport {
  self: PlaySpec =>

  val timeSource: TimeSource = new TimeSource {

    private val currentDate: Instant = SystemTimeSource.now()

    override def now(): Instant = currentDate
  }

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MetricOrchestrator].toInstance(mockMetricOrchestrator))
    .overrides(bind[TimeSource].toInstance(timeSource))
    .configure(
      "metrics.enabled"                              -> "false",
      "messages.retryFailedAfter"                    -> "1 day",
      "messages.retryInProgressAfter"                -> "1 day",
      "scheduling.sendAlerts.initialDelay"           -> "1 day",
      "scheduling.sendAlerts.interval"               -> "1 day",
      "scheduling.processNotifications.initialDelay" -> "1 day",
      "scheduling.processNotifications.interval"     -> "1 day",
      "scheduling.staticRenderer.interval"           -> "1 day",
      "lock.forceReleaseAfter"                       -> "1 second",
      "mongodb.uri"                                  -> "mongodb://localhost:27017/message",
      "quadient.message.ttl"                         -> "2 hours"
    )
    .injector()

  val messageRepository: MongoMessageRepository = injector.instanceOf[MongoMessageRepository]

  val messageAdminRepository: MessageAdminRepository = injector.instanceOf[MessageAdminRepository]

  lazy val mongoMessageRepository: PlayMongoRepository[Message] = messageRepository.repo

  lazy val lockRepository: LockRepository =
    new MongoLockRepository(mongoComponent, new CurrentTimestampSupport())

  // TODO: there appear to be tests for these methods?
  implicit class RepoForTest(val messagesRepo: MongoMessageRepository) {
    def insertAllUnique(messages: Seq[Message]): Seq[Future[Boolean]] =
      messages.map(messagesRepo.insertIfUnique)
  }

}
