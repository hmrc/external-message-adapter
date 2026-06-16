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

package uk.gov.hmrc.externalmessageadapter

import com.google.inject.name.Named
import com.google.inject.{ AbstractModule, Provides }
import net.codingwell.scalaguice.ScalaModule
import java.time.Instant
import org.apache.pekko.stream.scaladsl.Sink
import play.api.{ Configuration, Logging }
import play.api.libs.concurrent.PekkoGuiceSupport
import uk.gov.hmrc.externalmessageadapter.config.ExternalMessageAdapterCleanupConfig
import uk.gov.hmrc.externalmessageadapter.services.ExternalMessageAdapterCleanupService
import uk.gov.hmrc.externalmessageadapter.utils.TimeSource
import uk.gov.hmrc.mongo.lock.{ LockRepository, MongoLockRepository }
import uk.gov.hmrc.mongo.{ MongoComponent, TimestampSupport }

import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ Duration, DurationInt }

class ExternalMessageAdapterModule extends AbstractModule with ScalaModule with Logging {

  private val defaultQueryMaxTimeMs: Long = 1500000L // = 25 minutes
  private val defaultTTL: Duration = 365.days

  override def configure(): Unit = {
    // Bind configuration classes
    bind(classOf[ExternalMessageAdapterCleanupConfig]).asEagerSingleton()

    // Bind the cleanup service as eager singleton so it auto-starts
    bind(classOf[ExternalMessageAdapterCleanupService]).asEagerSingleton()
  }

  @Provides
  def sink(): Sink[Unit, _] = Sink.ignore

  @Provides
  @Singleton
  def lockRepositoryProvider(mongo: MongoComponent, timestampSupport: TimestampSupport)(implicit
    ec: ExecutionContext
  ): LockRepository =
    new MongoLockRepository(mongo, timestampSupport)

  @Singleton
  @Provides
  def systemTimeSourceProvider(): TimeSource = new TimeSource() {
    def now(): Instant = Instant.now
  }

  @Provides
  @Named("query-max-time-ms")
  @Singleton
  def queryMaxTimeMs(configuration: Configuration): Long =
    configuration
      .getOptional[Long]("queryMaxTimeMs")
      .getOrElse(defaultQueryMaxTimeMs)

  @Provides
  @Named("ttl-duration")
  @Singleton
  def messageRepoTTL(configuration: Configuration): Duration =
    configuration
      .getOptional[Duration]("quadient.message.ttl")
      .getOrElse(defaultTTL)

  @Provides
  @Named("audit-event-max-size")
  @Singleton
  def auditEventMaxSize(configuration: Configuration): Int =
    configuration
      .getOptional[Int]("auditEventMaxSize")
      .getOrElse(
        throw new RuntimeException("auditEventMaxSize not found in config")
      )

  @Provides
  @Named("app-name")
  @Singleton
  def appNameProvider(configuration: Configuration): String =
    configuration
      .getOptional[String]("appName")
      .getOrElse(throw new RuntimeException("App name not found in config"))

  @Provides
  @Named("denyListedFormIds")
  @Singleton
  def denyListedFormIds(configuration: Configuration): Seq[String] =
    configuration.getOptional[Seq[String]]("gmc.denylist").getOrElse(Seq())
}
