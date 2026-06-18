/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.services

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.mockito.ArgumentMatchers.{ any, anyString }
import org.mockito.Mockito.{ never, reset, times, verify, verifyNoMoreInteractions, when }
import org.mongodb.scala.bson.ObjectId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.common.message.model.Message
import uk.gov.hmrc.externalmessageadapter.config.ExternalMessageAdapterCleanupConfig
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.mongo.lock.{ Lock, LockRepository }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Succeeded

import java.time.{ Instant, LocalTime }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ExternalMessageAdapterCleanupServiceSpec extends PlaySpec with ScalaFutures with BeforeAndAfterEach {

  val testKit = ActorTestKit()
  implicit val system: ActorSystem = testKit.system.classicSystem
  implicit lazy val materializer: Materializer = Materializer(system)

  private val mockMessageRepository: MongoMessageRepository = mock[MongoMessageRepository]
  private val lockRepo: LockRepository = mock[LockRepository]
  private val lifecycle: ApplicationLifecycle = mock[ApplicationLifecycle]

  override def beforeEach(): Unit = {
    reset(mockMessageRepository)
    reset(lockRepo)
    reset(lifecycle)

    // Mock successful lock acquisition by default
    when(lockRepo.takeLock(anyString, anyString, any))
      .thenReturn(
        Future.successful(Some(Lock("test-lock-id", "test-owner", Instant.now(), Instant.now().plusSeconds(3600))))
      )
    when(lockRepo.releaseLock(anyString, anyString))
      .thenReturn(Future.successful(()))
  }

  "ExternalMessageAdapterCleanupService" should {

    "return job is not enabled message if the feature is not enabled" in new Setup {
      val service = cleanup(taskEnabled = false)
      service.execute().futureValue.message must include("not enabled")
    }

    "return 0 records when there are no old delivered records" in new Setup {
      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq.empty))

      val service = cleanup(taskEnabled = true)
      service.execute().futureValue.message must include("0 record(s)")
    }

    "return 2 records when there are two records to be deleted" in new Setup {
      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq(testMessageDelivered(), testMessageDelivered())))
      when(mockMessageRepository.removeById(any))
        .thenReturn(Future.successful(true))

      val service = cleanup(taskEnabled = true)
      service.execute().futureValue.message must include("2 record(s)")
    }

    "return 1 record when there are two records but unable to delete one" in new Setup {
      val id1 = new ObjectId()
      val id2 = new ObjectId()

      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq(testMessageDelivered(id1), testMessageDelivered(id2))))
      when(mockMessageRepository.removeById(id1))
        .thenReturn(Future.successful(true))
      when(mockMessageRepository.removeById(id2))
        .thenReturn(Future.successful(false))

      val service = cleanup(taskEnabled = true)
      service.execute().futureValue.message must include("1 record(s)")
    }

    "return not in active period message when outside time window" in new Setup {
      val service = cleanup(taskEnabled = true, startAt = "02:00", stopAt = "03:00")
      service.execute().futureValue.message must include("not in active period")
    }

    "should successfully process different batches" in new Setup {
      val batch1Messages = Seq(testMessageDelivered(), testMessageDelivered())
      val batch2Messages = Seq(testMessageDelivered())

      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(batch1Messages))
        .thenReturn(Future.successful(batch2Messages))
      when(mockMessageRepository.removeById(any))
        .thenReturn(Future.successful(true))

      val service = cleanup(taskEnabled = true)

      val result1 = service.execute().futureValue
      val result2 = service.execute().futureValue

      result1.message must include("2 record(s)")
      result2.message must include("1 record(s)")

      // Verify both batches were processed
      verify(mockMessageRepository, times(2)).findDeliveredRecords(any, any)
      verify(mockMessageRepository, times(3)).removeById(any) // 2 + 1 messages
      verifyNoMoreInteractions(mockMessageRepository)
    }

    "should recover after deletion failure" in new Setup {
      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq(testMessageDelivered())))
        .thenReturn(Future.successful(Seq(testMessageDelivered())))

      when(mockMessageRepository.removeById(any))
        .thenReturn(Future.failed(new RuntimeException("Delete failed"))) // First deletion fails
        .thenReturn(Future.successful(true)) // Second deletion succeeds

      val service = cleanup(taskEnabled = true)

      val result1 = service.execute().futureValue
      val result2 = service.execute().futureValue

      result1.message must include("0 record(s)")
      result2.message must include("1 record(s)")

      verify(mockMessageRepository, times(2)).findDeliveredRecords(any, any)
      verify(mockMessageRepository, times(2)).removeById(any)
    }

    "should handle deletion exceptions gracefully" in new Setup {
      val testMessage = testMessageDelivered()

      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq(testMessage)))
      when(mockMessageRepository.removeById(any))
        .thenReturn(Future.failed(new RuntimeException("Database error")))

      val service = cleanup(taskEnabled = true)
      val result = service.execute().futureValue
      result.message must include("0 record(s)") // Should handle error and count as failure
    }

    "emits elements correctly through stream" in new Setup {
      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq.empty))

      val service = cleanup(taskEnabled = true)
      service.start()

      probeSubscriber
        .request(2)
        .expectNext(())
        .expectNext(())
    }

    "should respect configured delays and intervals" in new Setup {
      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq.empty))

      val startTime = System.currentTimeMillis()
      val service = cleanup(taskEnabled = true)
      service.start()

      probeSubscriber
        .request(2)
        .expectNext(()) // Should arrive after ~100ms

      val firstElementTime = System.currentTimeMillis()
      (firstElementTime - startTime) must be >= 100L

      probeSubscriber
        .expectNext(()) // Should arrive after another ~200ms

      val secondElementTime = System.currentTimeMillis()
      (secondElementTime - firstElementTime) must be >= 180L // Give some leeway
    }

    "should successfully call repository during stream processing - single tick" in new Setup {
      val testMessage1 = testMessageDelivered()
      val testMessage2 = testMessageDelivered()

      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq(testMessage1, testMessage2)))
      when(mockMessageRepository.removeById(any))
        .thenReturn(Future.successful(true))

      val service = cleanup(taskEnabled = true)
      service.start()

      probeSubscriber
        .request(1)
        .expectNext(())

      // Give a short pause to ensure the execution completes
      Thread.sleep(50)

      verify(mockMessageRepository, times(1)).findDeliveredRecords(any, any)
      verify(mockMessageRepository, times(2)).removeById(any)
    }

    "should handle lock acquisition failure gracefully" in new Setup {
      // Override the default lock mock to return None (lock not acquired)
      when(lockRepo.takeLock(anyString, anyString, any))
        .thenReturn(Future.successful(None))

      when(mockMessageRepository.findDeliveredRecords(any, any))
        .thenReturn(Future.successful(Seq.empty))

      val service = cleanup(taskEnabled = true)
      service.start()

      probeSubscriber
        .request(1)
        .expectNext(())

      Thread.sleep(50)

      verify(mockMessageRepository, never()).findDeliveredRecords(any, any)
    }
  }

  trait Setup {
    val (probeSubscriber, probeSink) = TestSink.probe[Unit].preMaterialize()

    def testMessageDelivered(id: ObjectId = new ObjectId()): Message =
      MessageFixtures.testMessageWithoutContent(id = id, status = Succeeded)

    def cleanup(
      taskEnabled: Boolean,
      initialDelay: String = "100 milliseconds",
      interval: String = "200 milliseconds",
      deleteAfter: String = "1 hour",
      batchSize: Int = 100,
      startAt: String = "00:00",
      stopAt: String = "23:59",
      lockDuration: String = "1 hour"
    ) = {
      val configuration = Configuration(
        "scheduling.externalMessageAdapterCleanup.taskEnabled"  -> taskEnabled,
        "scheduling.externalMessageAdapterCleanup.initialDelay" -> initialDelay,
        "scheduling.externalMessageAdapterCleanup.interval"     -> interval,
        "scheduling.externalMessageAdapterCleanup.deleteAfter"  -> deleteAfter,
        "scheduling.externalMessageAdapterCleanup.batchSize"    -> batchSize,
        "scheduling.externalMessageAdapterCleanup.startAt"      -> startAt,
        "scheduling.externalMessageAdapterCleanup.stopAt"       -> stopAt,
        "scheduling.externalMessageAdapterCleanup.lockDuration" -> lockDuration
      )

      val config = new ExternalMessageAdapterCleanupConfig(configuration)

      new ExternalMessageAdapterCleanupService(
        mockMessageRepository,
        lockRepo,
        lifecycle,
        config,
        sink = probeSink
      )
    }
  }
}
