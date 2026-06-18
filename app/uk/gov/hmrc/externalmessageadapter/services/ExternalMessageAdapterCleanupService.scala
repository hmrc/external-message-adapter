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

package uk.gov.hmrc.externalmessageadapter.services

import org.apache.pekko.stream.{ KillSwitch, KillSwitches, Materializer }
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source }
import play.api.inject.ApplicationLifecycle
import play.api.Logging
import uk.gov.hmrc.externalmessageadapter.config.ExternalMessageAdapterCleanupConfig
import uk.gov.hmrc.externalmessageadapter.model.Result
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.common.message.model.Message
import uk.gov.hmrc.mongo.lock.{ LockRepository, LockService }

import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@Singleton
class ExternalMessageAdapterCleanupService @Inject() (
  mongoMessageRepository: MongoMessageRepository,
  lockRepo: LockRepository,
  lifecycle: ApplicationLifecycle,
  config: ExternalMessageAdapterCleanupConfig,
  sink: Sink[Unit, _] = Sink.ignore
)(implicit ec: ExecutionContext, mat: Materializer)
    extends LockService with Logging {

  logger.warn(s"ExternalMessageAdapterCleanupService constructor called - taskEnabled: ${config.taskEnabled}")

  private var killSwitch: Option[KillSwitch] = None

  override val lockRepository: LockRepository = lockRepo
  override val lockId: String = s"${config.name}-scheduled-job-lock"
  override val ttl: Duration = config.releaseLockAfter

  // Only run the stream if enabled in config
  if (config.taskEnabled) {
    start()
  }

  // Entrypoint
  def start(): Unit = {
    logger.warn(
      s"Stream starting: initialDelay: ${config.initialDelay}, interval: ${config.interval}, " +
        s"lock-ttl: $ttl, deleteAfter: ${config.deleteAfter}, batchSize: ${config.batchSize}, " +
        s"activeWindow: ${config.startAt} to ${config.stopAt}"
    )

    val (killSwitch, streamDone) =
      // Tick source, generates a Unit element to start execution periodically
      Source
        .tick(config.initialDelay, config.interval, tick = ())
        .mapAsync(1) { _ =>
          logger.debug(s"-> Tick received at ${Instant.now()}")
          executeInLock()
        }
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(sink)(Keep.both)
        .run() // Run forever

    this.killSwitch = Some(killSwitch)

    // Register clean-up on shutdown
    lifecycle.addStopHook { () =>
      logger.warn("Shutting down external message adapter cleanup stream...")
      killSwitch.shutdown() // Terminate the stream gracefully
      Future.successful(())
    }
  }

  // Attempt to acquire lock and run the body
  def executeInLock(): Future[Unit] =
    // Acquire a lock
    withLock {
      logger.debug(s"Lock acquired, calling execute() at ${Instant.now()}")
      // Execute this body when lock successfully acquired
      execute()
    }
      .map {
        case Some(result) =>
          logger.debug(s"Lock processing completed at ${Instant.now()}")
          logger.info(s"Successfully processed work under lock: $result")
        case None =>
          logger.info("Lock held by another instance; skipping")
      }
      .recover { case ex =>
        logger.error(s"Lock acquisition failed: $ex")
      }

  // Main clean-up logic
  def execute(): Future[Result] =
    if (config.taskEnabled && config.isActive) {
      logger.warn(s"Executing ExternalMessageAdapterCleanup job, enabled: ${config.taskEnabled}")

      val cutoffDate = Instant.now().minus(config.deleteAfter.toMillis, ChronoUnit.MILLIS)

      for {
        messages <- mongoMessageRepository.findDeliveredRecords(config.batchSize, cutoffDate)
        _ = logger.warn(s"Found ${messages.size} delivered record(s), ${config.deleteAfter} older")
        deletionResults <- deleteMessages(messages)
      } yield {
        val deletedRecords = deletionResults.count(identity)
        logger.warn(s"Cleaned up $deletedRecords record(s) from 'message'")
        Result(s"Completed the process '${config.name}' for $deletedRecords record(s)")
      }
    } else {
      val reason = if (!config.taskEnabled) "not enabled" else "not in active period"
      logger.info(s"${config.name} job is $reason")
      Future.successful(Result(s"${config.name} job is $reason"))
    }

  private def deleteMessages(messages: Seq[Message]): Future[Seq[Boolean]] =
    Future.traverse(messages) { message =>
      mongoMessageRepository
        .removeById(message.id)
        .recover { case ex =>
          logger.error(s"Failed to delete message ${message.id}: $ex")
          false
        }
    }
}
