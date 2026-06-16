/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.repository

import play.api.libs.json.Json
import uk.gov.hmrc.externalmessageadapter.GenerateRandom
import uk.gov.hmrc.externalmessageadapter.model.MessageFilter
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures._
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo

import java.time._
import org.mongodb.scala.Document
import org.mongodb.scala.bson.{ BsonDocument, ObjectId }
import org.mongodb.scala.model.{ Filters, Updates }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.{ BeforeAndAfterEach, LoneElement }
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import uk.gov.hmrc.common.message.model.{ AlertDetails, Delivered, Details, EmailAlert, ExternalRef, Message, Regime, RenderUrl, TaxpayerName }
import uk.gov.hmrc.domain.{ Org, SaUtr }
import uk.gov.hmrc.externalmessageadapter.utils.SystemTimeSource
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import org.mongodb.scala.{ ObservableFuture, SingleObservableFuture }
import org.mongodb.scala.bsonDocumentToDocument
import org.mongodb.scala.documentToUntypedDocument

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.MILLIS
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

class MongoMessageRepositorySpec
    extends PlaySpec with BeforeAndAfterEach with GuiceOneAppPerSuite with MessageRepositoryForTest with ScalaFutures
    with LoneElement with IntegrationPatience {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def mongoUri = "mongodb://localhost:27017/message"

  protected override def beforeEach(): Unit = {
    messageAdminRepository.deleteAll().futureValue
    mongoMessageRepository.ensureIndexes().futureValue
    ()
  }

  "The insertAllUnique method" must {

    "insert a single record into an empty collection" in {

      val message = messageForSA()
      messageRepository.insertIfUnique(message).futureValue
      messageRepository.repo.collection.find().toFuture().futureValue mustBe Seq(message)
    }

    "insert a record with tags" in {

      val message: Message = testMessageWithoutContent(tags = Some(Map("notificationType" -> "Direct Debit")))
      messageRepository.insertIfUnique(message).futureValue
      messageRepository.repo.collection.find().toFuture().futureValue mustBe Seq(message)
    }

    "insert five unique records into an empty collection" in {

      val n1 = testMessageWithoutContent(recipientId = SaUtr("AA111111A"))
      val n2 = testMessageWithoutContent(recipientId = SaUtr("BB111111A"))
      val n3 = testMessageWithoutContent(recipientId = SaUtr("CC111111A"))
      val n4 = testMessageWithoutContent(recipientId = SaUtr("DD111111A"))
      val n5 = testMessageWithoutContent(recipientId = SaUtr("EE111111A"))

      val messages: Seq[Message] = Seq(n1, n2, n3, n4, n5)

      val insertResult = messageRepository.insertAllUnique(messages)

      val result = Future.sequence(insertResult).futureValue
      result.count(_ == true) mustBe 5
      result.count(_ == false) mustBe 0

      val actual: Seq[Message] = messageRepository.repo.collection.find().toFuture().futureValue

      actual mustEqual messages
    }

    "insert four unique records and ignore the duplicate" in {

      val n1 = testMessageWithoutContent(recipientId = SaUtr("AA111111A"), hash = "weHaveTheSameHash")
      val n2 = testMessageWithoutContent(recipientId = SaUtr("BB111111A"))
      val n3 = testMessageWithoutContent(recipientId = SaUtr("CC111111A"))
      val n4 = testMessageWithoutContent(recipientId = SaUtr("AA111111A"), hash = "weHaveTheSameHash")
      val n5 = testMessageWithoutContent(recipientId = SaUtr("EE111111A"))

      val insertResult = messageRepository.insertAllUnique(Seq(n1, n2, n3, n4, n5))

      val result = Future.sequence(insertResult).futureValue
      result.count(_ == true) mustBe 4
      result.count(_ == false) mustBe 1

      val actual: Seq[Message] = messageRepository.repo.collection.find().toFuture().futureValue
      actual.size mustBe 4
      actual must contain.allOf(n2, n3, n5)
      actual must contain.oneOf(n1, n4)
    }
  }

  "Inserting two PrintSuppressionNotification messages with the insertAllUnique method" must {

    "only insert one of them if they have same hash" in {
      val hashValue = "weHaveSameHash"
      val message1 = testMessageWithoutContent(hash = hashValue)
      val message2 = testMessageWithoutContent(hash = hashValue)

      message2 must not be message1 // Confirming they have different database ids

      val insertResult = messageRepository.insertAllUnique(Seq(message1, message2))

      val result = Future.sequence(insertResult).futureValue
      result.count(_ == true) mustBe 1
      result.count(_ == false) mustBe 1

      val actual = messageRepository.repo.collection.find().toFuture().futureValue
      actual must have size 1
      actual must contain.oneOf(message1, message2)
    }

    "treat them as different if the hash is different" in {

      val message1 = testMessageWithoutContent(recipientId = SaUtr("5554444333"))
      val message2 = testMessageWithoutContent(recipientId = SaUtr("5554444332"))

      val insertResult = messageRepository.insertAllUnique(Seq(message1, message2))

      val result = Future.sequence(insertResult).futureValue
      result.count(_ == true) mustBe 2
      result.count(_ == false) mustBe 0

      val actual = messageRepository.repo.collection.find().toFuture().futureValue
      actual must have size 2
      actual must contain.allOf(message1, message2)
    }
  }

  "findById" must {

    "return None if the object isn't in the database" in {
      messageRepository.findById(new ObjectId(), withRescinded = true).futureValue mustBe None
    }

    "return the message if it is in the database" in {
      // given
      val message = testMessageWithoutContent()
      val messageId = message.id
      messageRepository.repo.collection.insertOne(message).toFuture().futureValue

      // then
      messageRepository.findById(messageId).futureValue mustBe Some(message)
    }

    "return message if it is in the database, even if it doesn't have a lastUpdated date" in {
      // given
      val message = testMessageWithoutContent()
      val messageId = message.id
      messageRepository.repo.collection.insertOne(message).toFuture().futureValue
      messageRepository.repo.collection
        .findOneAndUpdate(Filters.eq("status", ToDo.name), Updates.unset("lastUpdated"))
        .toFuture()
        .futureValue

      // then
      messageRepository.findById(messageId).futureValue mustBe Some(message.copy(lastUpdated = None))
    }
  }

  "findByExternalRefId" must {

    "return None if the object isn't in the database" in {
      messageRepository.findByExternalRefId("not-existing").futureValue mustBe None
    }

    "return the message if it is in the database" in {
      // given
      val externalRef = ExternalRef("refId", "gmc")
      val message = testMessageWithoutContent(externalRef = Some(externalRef))
      messageRepository.repo.collection.insertOne(message).toFuture().futureValue

      // then
      messageRepository.findByExternalRefId(externalRef.id).futureValue mustBe Some(message)
    }

    "return message if it is in the database, even if it doesn't have a lastUpdated date" in {
      // given
      val externalRef = ExternalRef("refId", "gmc")
      val message = testMessageWithoutContent(externalRef = Some(externalRef))
      messageRepository.repo.collection.insertOne(message).toFuture().futureValue
      messageRepository.repo.collection
        .findOneAndUpdate(Filters.eq("status", ToDo.name), Updates.unset("lastUpdated"))
        .toFuture()
        .futureValue

      // then
      messageRepository.findByExternalRefId(externalRef.id).futureValue mustBe Some(message.copy(lastUpdated = None))
    }
  }

  "reading existing data" must {
    import MongoJavatimeFormats.Implicits.jatInstantFormat
    "work for the current data schema without recipientName" in {
      val objectId = new ObjectId("5398284e0100000100050644")
      val jsonAsBson = BsonDocument.apply(
        Json
          .obj(
            "_id"      -> Json.obj("$oid" -> "5398284e0100000100050644"),
            "entityId" -> "theEntityId12431234",
            "recipient" -> Json.obj(
              "regime"     -> "sa",
              "identifier" -> Json.obj("name" -> "sautr", "value" -> "1554444333")
            ),
            "subject" -> "Blah blah blah",
            "body" -> Json.obj(
              "form"         -> "SA300",
              "suppressedAt" -> "2013-01-02",
              "detailsId"    -> "C0123456781234568",
              "type"         -> "print-suppression-notification",
              "issueDate"    -> s"${LocalDate.now}"
            ),
            "validFrom" -> "2013-12-01",
            "alerts" -> Json.obj(
              "emailAddress" -> "a@b.com",
              "alertTime" -> Json.toJson(
                Instant.parse(DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(0)))
              ),
              "success" -> true
            ),
            "hash" -> "cYC+iFX+j2+r/hrY+hbOfDC/BwjR1Wg++rk4dIC8aWI=",
            "readTime" -> Json.toJson(
              Instant.parse(DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(1)))
            ),
            "status" -> "todo",
            "lastUpdated" -> Json.toJson(
              Instant.parse(DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(3)))
            )
          )
          .toString()
      )
      mongoComponent.database.getCollection("quadient.message").insertOne(jsonAsBson).toFuture().futureValue
      messageRepository.repo.collection.find().toFuture().futureValue.size mustBe 1
      messageRepository.findById(objectId).futureValue must contain(
        Message(
          id = objectId,
          recipient = MessageFixtures.createTaxEntity(SaUtr("1554444333")),
          subject = "Blah blah blah",
          body = Some(
            Details(
              Some(form),
              Some("print-suppression-notification"),
              Some("2013-01-02"),
              Some("C0123456781234568"),
              None,
              None,
              Some(LocalDate.now)
            )
          ),
          validFrom = LocalDate.of(2013, 12, 1),
          alertFrom = None,
          alertDetails = AlertDetails("newMessageAlert", None, Map()),
          alerts = Some(
            EmailAlert(
              emailAddress = Some("a@b.com"),
              alertTime = Instant.parse(
                DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(0))
              ),
              success = true,
              failureReason = None
            )
          ),
          readTime = Some(
            Instant.parse(DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(1)))
          ),
          status = ToDo,
          lastUpdated = Some(Instant.ofEpochSecond(3)),
          hash = "cYC+iFX+j2+r/hrY+hbOfDC/BwjR1Wg++rk4dIC8aWI=",
          statutory = false,
          renderUrl = RenderUrl("sa-message-renderer", "/messages/sa/1554444333/5398284e0100000100050644"),
          content = None,
          sourceData = None
        )
      )
    }

    "work for the current data schema with recipientName" in {
      val objectId = new ObjectId("5398284e0100000100050644")
      val taxPayername = TaxpayerName(title = Some("Dr"), forename = Some("Bruce"), surname = Some("Banner"))
      val json = BsonDocument.apply(
        Json
          .obj(
            "_id"      -> Json.obj("$oid" -> "5398284e0100000100050644"),
            "entityId" -> "theEntityId12431234",
            "recipient" -> Json.obj(
              "regime"     -> "sa",
              "identifier" -> Json.obj("name" -> "sautr", "value" -> "1554444333")
            ),
            "subject" -> "Blah blah blah",
            "body" -> Json.obj(
              "form"         -> "SA300",
              "suppressedAt" -> "2013-01-02",
              "detailsId"    -> "C0123456781234568",
              "type"         -> "print-suppression-notification",
              "issueDate"    -> s"${LocalDate.now}"
            ),
            "validFrom" -> "2013-12-01",
            "alerts" -> Json.obj(
              "emailAddress" -> "a@b.com",
              "alertTime"    -> Instant.ofEpochSecond(0),
              "success"      -> true
            ),
            "alertDetails" -> Json.obj(
              "alertFrom"  -> "2013-01-02",
              "templateId" -> "templateId",
              "data"       -> Json.obj(),
              "recipientName" -> Json.obj(
                "title"    -> "Dr",
                "forename" -> "Bruce",
                "surname"  -> "Banner"
              )
            ),
            "hash"        -> "cYC+iFX+j2+r/hrY+hbOfDC/BwjR1Wg++rk4dIC8aWI=",
            "readTime"    -> Instant.ofEpochSecond(1),
            "status"      -> "todo",
            "lastUpdated" -> Instant.ofEpochSecond(3)
          )
          .toString()
      )

      mongoComponent.database.getCollection("quadient.message").insertOne(json).toFuture().futureValue
      messageRepository.repo.collection.find().toFuture().futureValue.size mustBe 1
      messageRepository.findById(objectId).futureValue must contain(
        Message(
          id = objectId,
          recipient = MessageFixtures.createTaxEntity(SaUtr("1554444333")),
          subject = "Blah blah blah",
          body = Some(
            Details(
              Some("SA300"),
              Some("print-suppression-notification"),
              Some("2013-01-02"),
              Some("C0123456781234568")
            )
          ),
          validFrom = LocalDate.of(2013, 12, 1),
          alertFrom = None,
          alertDetails = AlertDetails("templateId", Some(taxPayername), Map()),
          alerts = Some(
            EmailAlert(
              emailAddress = Some("a@b.com"),
              alertTime = Instant.ofEpochSecond(0),
              success = true,
              failureReason = None
            )
          ),
          readTime = Some(Instant.ofEpochSecond(1)),
          status = ToDo,
          lastUpdated = Some(Instant.ofEpochSecond(3)),
          hash = "cYC+iFX+j2+r/hrY+hbOfDC/BwjR1Wg++rk4dIC8aWI=",
          statutory = false,
          renderUrl = RenderUrl("sa-message-renderer", "/messages/sa/1554444333/5398284e0100000100050644"),
          sourceData = None
        )
      )
    }
  }

  "find by id" must {
    "return missing if not yet valid (validFrom)" in {
      val message = testMessageWithoutContent(validFrom = LocalDate.now().plusDays(1))
      messageRepository.save(message).futureValue
      messageRepository.findById(message.id).futureValue must not be defined
    }
    "return missing if valid and verificationBrake true" in {
      val message = testMessageWithoutContent(validFrom = LocalDate.now().minusDays(1), verificationBrake = Some(true))
      messageRepository.save(message).futureValue
      messageRepository.findById(message.id).futureValue must not be defined
    }

    "return message if valid and verificationBrake is false" in {
      val message = testMessageWithoutContent(validFrom = LocalDate.now().minusDays(1), verificationBrake = Some(false))
      messageRepository.save(message).futureValue
      messageRepository.findById(message.id).futureValue must be(defined)
    }

    "return message if valid and verificationBrake is not defined" in {
      val message = testMessageWithoutContent(validFrom = LocalDate.now().minusDays(1), verificationBrake = None)
      messageRepository.save(message).futureValue
      messageRepository.findById(message.id).futureValue must be(defined)
    }
  }

  "indexes" must {
    "match the expected number on ensureIndex" in {
      messageRepository.repo.collection.listIndexes().toFuture().futureValue must have size 11
    }

    "replace the value 'ttl-duration' in the index 'deliveredOnIndex' when the configuration value 'quadient.message.ttl' has changed" in {
      val indexList = messageRepository.repo.collection.listIndexes().toFuture().futureValue
      val index: Document = indexList.find(_.getString("name") == "deliveredOnIndex").get
      val ttl =
        Try(index("expireAfterSeconds").asInt32().getValue).getOrElse(index("expireAfterSeconds").asInt64().getValue)
      ttl must be(7200) // 2 hours
    }
  }

  "findBy taxIdentifier" must {
    val nino = GenerateRandom.nino()
    val otherNino = GenerateRandom.nino()
    val utr = GenerateRandom.utr()
    val otherUtr = GenerateRandom.utr()

    "return 0 if the repository is empty" in {

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(utr.name), Seq[Regime.Value]())
      messageRepository.findBy(Set(utr), true).futureValue mustBe Seq.empty
    }

    "return nino messages in correct order (newest first)" in {
      val testMessage1 = testMessageWithoutContent(recipientId = nino)
      val testMessage2 =
        testMessageWithoutContent(
          recipientId = nino,
          readTime = Some(SystemTimeSource.now().minus(1, ChronoUnit.HOURS))
        )

      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection.insertOne(testMessage2).toFuture()
      } yield (result1, result2)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(nino.name), Seq[Regime.Value]())
      messageRepository.findBy(Set(nino)).futureValue must contain.inOrderOnly(testMessage2, testMessage1)
    }

    "return nino messages in correct order (newest first) if requested by paye regime" in {
      val testMessage1 = testMessageWithoutContent(recipientId = nino)
      val testMessage2 =
        testMessageWithoutContent(
          recipientId = nino,
          readTime = Some(SystemTimeSource.now().minus(1, ChronoUnit.HOURS))
        )

      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection.insertOne(testMessage2).toFuture()
      } yield (result1, result2)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(), Seq[Regime.Value](Regime.paye))
      messageRepository
        .findBy(Set(nino))
        .futureValue must contain.inOrderOnly(testMessage2, testMessage1)
    }

    "return utr messages in correct order (newest first)" in {
      val testMessage1 = testMessageWithoutContent(recipientId = utr)
      val testMessage2 =
        testMessageWithoutContent(recipientId = utr, readTime = Some(SystemTimeSource.now().minus(1, ChronoUnit.HOURS)))
      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection.insertOne(testMessage2).toFuture()
      } yield (result1, result2)).futureValue

      implicit val messageFilter = MessageFilter(Seq(utr.name), Seq[Regime.Value]())
      messageRepository
        .findBy(Set(utr))
        .futureValue must contain.inOrderOnly(testMessage2, testMessage1)
    }

    "only return valid nino messages" in {
      val testMessage1 =
        testMessageWithoutContent(recipientId = nino, validFrom = LocalDate.now(ZoneOffset.UTC).minusDays(1))
      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection
                     .insertOne(
                       testMessageWithoutContent(
                         recipientId = nino,
                         validFrom = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                       )
                     )
                     .toFuture()
      } yield (result1, result2)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(nino.name), Seq[Regime.Value]())
      messageRepository.findBy(Set(nino)).futureValue mustBe Seq(testMessage1)
    }

    "only return valid utr messages" in {
      val testMessage1 =
        testMessageWithoutContent(recipientId = utr, validFrom = LocalDate.now(ZoneOffset.UTC).minusDays(1))
      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection
                     .insertOne(
                       testMessageWithoutContent(
                         recipientId = utr,
                         validFrom = LocalDate.now(ZoneOffset.UTC).plusDays(1)
                       )
                     )
                     .toFuture()
      } yield (result1, result2)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(utr.name), Seq[Regime.Value]())
      messageRepository.findBy(Set(utr)).futureValue mustBe Seq(testMessage1)
    }

    "only return messages for a given nino even if there are other nino and utr messages" in {
      val testMessage1 = testMessageWithoutContent(recipientId = nino)
      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection
                     .insertOne(testMessageWithoutContent(recipientId = otherNino))
                     .toFuture()
        result3 <- mongoMessageRepository.collection.insertOne(testMessageWithoutContent(recipientId = utr)).toFuture()
      } yield (result1, result2, result3)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(nino.name), Seq[Regime.Value]())
      messageRepository.findBy(Set(nino)).futureValue mustBe Seq(testMessage1)
    }

    "only return messages for a given utr even if there are other nino and utr messages" in {

      val testMessage1 = testMessageWithoutContent(recipientId = utr)
      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessageWithoutContent(recipientId = nino)).toFuture()
        result3 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection
                     .insertOne(testMessageWithoutContent(recipientId = otherUtr))
                     .toFuture()
      } yield (result1, result2, result3)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(utr.name), Seq[Regime.Value]())
      messageRepository.findBy(Set(utr)).futureValue mustBe Seq(testMessage1)
    }

    "only return messages for a given regime if there are other  messages" in {
      val testMessage1 = testMessageWithoutContent(recipientId = utr)
      (for {
        result1 <- mongoMessageRepository.collection.insertOne(testMessageWithoutContent(recipientId = nino)).toFuture()
        result3 <- mongoMessageRepository.collection.insertOne(testMessage1).toFuture()
        result2 <- mongoMessageRepository.collection
                     .insertOne(testMessageWithoutContent(recipientId = otherUtr))
                     .toFuture()
      } yield (result1, result2, result3)).futureValue

      implicit val messageFilter: MessageFilter = MessageFilter(Seq(), Seq[Regime.Value](Regime.sa))
      messageRepository.findBy(Set(utr)).futureValue mustBe Seq(testMessage1)
    }

    "return an empty Sequence if find is called with an unsupported Tax Identifier" in {

      implicit val messageFilter: MessageFilter = MessageFilter(Seq("HMRC"), Seq[Regime.Value]())
      messageRepository.findBy(Set(Org("HMRC"))).futureValue mustBe Seq.empty
    }
  }

  "removeById" must {
    "delete the document from the collection when present" in {
      // given
      val message = testMessageWithoutContent()
      val messageId = message.id
      messageRepository.repo.collection.insertOne(message).toFuture().futureValue

      // when
      messageRepository.removeById(messageId).futureValue

      // then
      messageRepository.findById(messageId).futureValue mustBe None
    }
  }

  "processDeliveredEvent" must {
    "set the status to delivered" in {
      val messageId = UUID.randomUUID().toString
      val externalRef = Some(ExternalRef(id = messageId, "gmc"))
      val message = testMessageWithoutContent(externalRef = externalRef)
      val deliveredOn = Instant.now().truncatedTo(MILLIS)
      messageRepository.repo.collection.insertOne(message).toFuture().futureValue
      messageRepository.processDeliveredEvent(messageId, deliveredOn).futureValue
      val processedMessage: Option[Message] = messageRepository.findByExternalRefId(messageId).futureValue
      processedMessage.get.deliveredOn.get.toString mustBe deliveredOn.toString
      processedMessage.get.mailgunStatus.get mustBe uk.gov.hmrc.common.message.model.Delivered
    }

    "do nothing if the message does not exist" in {
      val messageId = UUID.randomUUID().toString
      val deliveredOn = Instant.now()
      messageRepository.processDeliveredEvent(messageId, deliveredOn).futureValue
      val processedMessage: Option[Message] = messageRepository.findByExternalRefId(messageId).futureValue
      processedMessage mustBe None
    }
  }

  "findDeliveredRecords" must {
    "return only the messages having status 'delivered' and expired" in {
      val m1 = testMessageWithoutContent(mailgunStatus = Some(Delivered))
      val m2 =
        testMessageWithoutContent(
          mailgunStatus = Some(Delivered),
          deliveredOn = Some(SystemTimeSource.now().minus(1, ChronoUnit.DAYS))
        )
      val m3 = testMessageWithoutContent(mailgunStatus = None)

      (for {
        result1 <- mongoMessageRepository.collection.insertOne(m1).toFuture()
        result3 <- mongoMessageRepository.collection.insertOne(m2).toFuture()
        result2 <- mongoMessageRepository.collection.insertOne(m3).toFuture()
      } yield (result1, result2, result3)).futureValue

      messageRepository.findDeliveredRecords(100, SystemTimeSource.now()).futureValue mustBe Seq(m2)
    }
  }

}
