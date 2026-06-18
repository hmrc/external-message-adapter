/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.validators

import com.codahale.metrics.SharedMetricRegistries
import junit.framework.TestCase
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.mongodb.scala.bson.ObjectId
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.NOT_FOUND
import play.api.i18n.{ Lang, Messages, MessagesApi, MessagesImpl }
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.{ Injector, bind }
import play.api.libs.json.*
import play.api.mvc.Request
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.common.message.model.*
import uk.gov.hmrc.domain.{ Nino, SaUtr }
import uk.gov.hmrc.externalmessageadapter.GenerateRandom
import uk.gov.hmrc.externalmessageadapter.connectors.*
import uk.gov.hmrc.externalmessageadapter.model.*
import uk.gov.hmrc.externalmessageadapter.repository.MongoMessageRepository
import uk.gov.hmrc.externalmessageadapter.util.MessageFixtures
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.play.audit.http.connector.AuditConnector

import java.time.{ Instant, LocalDate }
import java.util.UUID
import scala.concurrent.Future

class MessagesUtilSpecNew extends PlaySpec with MockitoSugar with ScalaFutures {

  lazy val mockRepo = mock[MongoMessageRepository]
  lazy val mockAuthConnector = mock[AuthIdentifiersConnector]
  lazy val mockAuditConnector = mock[AuditConnector]
  lazy val mockMessageConnector = mock[MessageConnector]
  lazy val mockPreferencesConnector = mock[PreferencesConnector]
  lazy val mockEnrolmentProxyConnector = mock[EnrolmentProxyConnector]
  lazy val mockTaxpayerNameConnector = mock[TaxpayerNameConnector]
  implicit val hc: HeaderCarrier = HeaderCarrier()

  val responseAsJson = (reason: String, failureId: String) =>
    Json.parse(s"""{"failureId":"$failureId","reason":"$reason"}""")

  private val injector: Injector = new GuiceApplicationBuilder()
    .overrides(bind[MongoMessageRepository].toInstance(mockRepo))
    .overrides(bind[AuthIdentifiersConnector].toInstance(mockAuthConnector))
    .overrides(bind[AuditConnector].toInstance(mockAuditConnector))
    .overrides(bind[MessageConnector].toInstance(mockMessageConnector))
    .overrides(bind[EnrolmentProxyConnector].toInstance(mockEnrolmentProxyConnector))
    .overrides(bind[PreferencesConnector].toInstance(mockPreferencesConnector))
    .overrides(bind[TaxpayerNameConnector].toInstance(mockTaxpayerNameConnector))
    .configure(
      "metrics.enabled" -> "false"
    )
    .injector()

  val messagesApi: MessagesApi = injector.instanceOf[MessagesApi]
  def messagesInWelsh(): Messages = MessagesImpl(Lang("cy"), messagesApi)
  def messagesInEnglish(): Messages = MessagesImpl(Lang("en"), messagesApi)

  lazy val messagesUtil: MessagesUtil = injector.instanceOf[MessagesUtil]
  val utr: SaUtr = GenerateRandom.utr()
  val nino: Nino = GenerateRandom.nino()

  "checking message source" must {

    "for GMC return true when a message is indeed targeted for GMC" in new TestCase {
      private val messageWithUpperCasedSource: Message =
        MessageFixtures.testMessageWithoutContent(recipientId = utr, externalRef = Some(ExternalRef("id", "GMC")))
      private val messageWithLowerCasedSource: Message =
        MessageFixtures.testMessageWithoutContent(recipientId = utr, externalRef = Some(ExternalRef("id", "gmc")))
      MessagesUtil.isGmc(messageWithUpperCasedSource) mustBe true
      MessagesUtil.isGmc(messageWithLowerCasedSource) mustBe true
    }

    "for non-GMC return false when a message is not targeted for GMC" in new TestCase {
      private val message: Message =
        MessageFixtures.testMessageWithoutContent(recipientId = utr, externalRef = Some(ExternalRef("id", "not-gmc")))
      MessagesUtil.isGmc(message) mustBe false
    }
    SharedMetricRegistries.clear()
  }

  "extractIssueDate - DC-1729" must {

    val details = Details(
      form = None,
      `type` = None,
      suppressedAt = None,
      detailsId = None,
      paperSent = None,
      batchId = None,
      issueDate = Some(LocalDate.parse("2019-01-01"))
    )

    val message1 = Message(
      id = new ObjectId(),
      recipient = TaxEntity(Regime.sa, SaUtr("1234567890"), Some("test@test.com")),
      subject = "RE: Subject",
      body = Some(details),
      validFrom = LocalDate.parse("2019-02-27"),
      alertFrom = None,
      alertDetails = AlertDetails("template-id", None, Map()),
      alertQueue = Some("queue1"),
      lastUpdated = None,
      hash = "*hash*",
      statutory = true,
      renderUrl = RenderUrl(service = "my-service", url = "service-url"),
      sourceData = None,
      externalRef = None
    )

    "use issueDate if supplied" in new TestCase {
      MessagesUtil.extractMessageDate(message1) mustBe "01 January 2019"
    }

    val message2 = Message(
      recipient = TaxEntity(Regime.sa, SaUtr("1234567890"), Some("test@test.com")),
      subject = "Subject goes here",
      body = None,
      validFrom = LocalDate.parse("2019-02-27"),
      alertFrom = None,
      alertDetails = AlertDetails("template-id", None, Map()),
      lastUpdated = None,
      hash = "*hash*",
      statutory = true,
      renderUrl = RenderUrl(service = "my-service", url = "service-url"),
      sourceData = None
    )

    "use validFrom if issueDate is not supplied" in new TestCase {
      MessagesUtil.extractMessageDate(message2) mustBe "27 February 2019"
    }
  }

  "localizedExtractIssueDate - DC-4021" must {

    val details = Details(
      form = None,
      `type` = None,
      suppressedAt = None,
      detailsId = None,
      paperSent = None,
      batchId = None,
      issueDate = Some(LocalDate.parse("2019-01-01"))
    )

    val message1 = Message(
      id = new ObjectId(),
      recipient = TaxEntity(Regime.sa, SaUtr("1234567890"), Some("test@test.com")),
      subject = "RE: Subject",
      body = Some(details),
      validFrom = LocalDate.parse("2019-02-27"),
      alertFrom = None,
      alertDetails = AlertDetails("template-id", None, Map()),
      alertQueue = Some("queue1"),
      lastUpdated = None,
      hash = "*hash*",
      statutory = true,
      renderUrl = RenderUrl(service = "my-service", url = "service-url"),
      sourceData = None,
      externalRef = None
    )

    "use issueDate if supplied - English" in new TestCase {
      implicit val englishMessages: Messages = messagesInEnglish()
      MessagesUtil.localizedExtractMessageDate(message1) mustBe "01 January 2019"
    }

    "use issueDate if supplied - Welsh" in new TestCase {
      implicit val welshMessages: Messages = messagesInWelsh()
      MessagesUtil.localizedExtractMessageDate(message1) mustBe "1 Ionawr 2019"
    }

    val message2 = Message(
      recipient = TaxEntity(Regime.sa, SaUtr("1234567890"), Some("test@test.com")),
      subject = "Subject goes here",
      body = None,
      validFrom = LocalDate.parse("2019-02-27"),
      alertFrom = None,
      alertDetails = AlertDetails("template-id", None, Map()),
      lastUpdated = None,
      hash = "*hash*",
      statutory = true,
      renderUrl = RenderUrl(service = "my-service", url = "service-url"),
      sourceData = None
    )

    "use validFrom if issueDate is not supplied" in new TestCase {
      implicit val englishMessages: Messages = messagesInEnglish()
      MessagesUtil.localizedExtractMessageDate(message2) mustBe "27 February 2019"
    }

    "use validFrom if issueDate is not supplied - Welsh" in new TestCase {
      implicit val welshhMessages: Messages = messagesInWelsh()
      MessagesUtil.localizedExtractMessageDate(message2) mustBe "27 Chwefror 2019"
    }
  }

  "handleBiggerContent function" must {
    "replace sourceData and content values with alternative text if both exists" in new TestCase() {
      val message =
        Json
          .parse(s"""
                    | {
                    |   "sourceData": "Test subject",
                    |   "foo": "not important",
                    |   "content": "test content"
                    | }
    """.stripMargin)
          .as[JsObject]
      Json.parse(messagesUtil.handleBiggerContent(message)) mustBe
        Json.parse(s"""
                      | {
                      |   "sourceData": "sourceData is removed to reduce size",
                      |   "foo": "not important",
                      |   "content": "content is removed to reduce size"
                      | }
    """.stripMargin)
    }

    "replace content with alternative text if only content is present" in new TestCase() {
      val message =
        Json
          .parse(s"""
                    | {
                    |   "foo": "not important",
                    |   "content": "test content"
                    | }
    """.stripMargin)
          .as[JsObject]
      Json.parse(messagesUtil.handleBiggerContent(message)) mustBe
        Json.parse(s"""
                      | {
                      |   "foo": "not important",
                      |   "content": "content is removed to reduce size"
                      | }
    """.stripMargin)
    }

    "replace sourceData with alternative text only if sourceData is present" in new TestCase() {
      val message =
        Json
          .parse(s"""
                    | {
                    |   "foo": "not important",
                    |   "sourceData": "Test subject"
                    | }
    """.stripMargin)
          .as[JsObject]
      Json.parse(messagesUtil.handleBiggerContent(message)) mustBe
        Json.parse(s"""
                      | {
                      |   "foo": "not important",
                      |   "sourceData": "sourceData is removed to reduce size"
                      | }
    """.stripMargin)
    }

    "return message data as it is if both sourceData and content are missing" in new TestCase() {
      val message =
        Json
          .parse(s"""
                    | {
                    |   "foo": "not important"
                    | }
    """.stripMargin)
          .as[JsObject]
      Json.parse(messagesUtil.handleBiggerContent(message)) mustBe
        Json.parse(s"""
                      | {
                      |   "foo": "not important"
                      | }
    """.stripMargin)
    }

  }

  "checkTaxpayer" must {

    "handle message with unverified email for recipient" in new TestCase() {
      when(mockPreferencesConnector.verifiedEmailAddress(any[TaxEntity])(any[HeaderCarrier]))
        .thenReturn(Future.successful(VerifiedEmailNotFound("EMAIL_ADDRESS_NOT_VERIFIED")))
      val message: Message =
        MessageFixtures.testMessageWithoutContent(recipientId = utr, externalRef = Some(ExternalRef("id", "testId")))

      val request: Request[JsValue] = FakeRequest().withBody(Json.obj())
      val hc = HeaderCarrier()
      val result = messagesUtil.checkTaxpayerAndProcessMessage(message)(request, hc)
      status(result) mustBe NOT_FOUND
      val expectedErrorBody =
        responseAsJson(
          "The backend has rejected the message due to not being able to verify the email address.",
          "EMAIL_NOT_VERIFIED"
        )
      contentAsJson(result) mustBe expectedErrorBody
    }
  }

  "checkEnrolments" must {

    "check when there are no enrolments for the given enrolment key" in new TestCase() {
      when(mockEnrolmentProxyConnector.enrolments(any[Enrolments])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right("enrolment" -> None)))
      when(mockPreferencesConnector.markPreferencesForDeEnrolment(any[TaxEntity])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(OK, "")))

      val message: Message = MessageFixtures.testMessageWithoutContent(recipientId = utr)

      val request: Request[JsValue] = FakeRequest().withBody(Json.obj())
      val hc = HeaderCarrier()
      val result = messagesUtil.checkEnrolmentsAndProcessMessage(message)(request, hc)

      status(result) mustBe NOT_FOUND
      Json.stringify(contentAsJson(result)) must include("TAXPAYER_NOT_FOUND")
    }

    "check when there are enrolments but no active users for the given enrolment key" in new TestCase() {
      when(mockEnrolmentProxyConnector.enrolments(any[Enrolments])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right("enrolment" -> Some(Users(List("123456", "789012"))))))
      when(mockEnrolmentProxyConnector.hasActiveUsers(any[List[String]], any[String])(any[HeaderCarrier]))
        .thenReturn(Future.successful(false))
      when(mockPreferencesConnector.markPreferencesForDeEnrolment(any[TaxEntity])(any[HeaderCarrier]))
        .thenReturn(Future.successful(HttpResponse(OK, "")))

      val message: Message = MessageFixtures.testMessageWithoutContent(recipientId = utr)

      val request: Request[JsValue] = FakeRequest().withBody(Json.obj())
      val hc = HeaderCarrier()
      val result = messagesUtil.checkEnrolmentsAndProcessMessage(message)(request, hc)

      status(result) mustBe NOT_FOUND
      Json.stringify(contentAsJson(result)) must include("TAXPAYER_NOT_FOUND")
    }
  }

  "cleanUpSubjectAndContent" must {
    "preserve the image src attribute in the message content when the protocol is present" in {
      val imgWithSrcProtocol =
        "<img src=\"https://www.tax.service.gov.uk/assets/4.8.0/images/direct-debit-logo.png\" alt=\"Direct Debit logo\" />"
      val message = MessageFixtures.testMessageWithContent(
        id = new ObjectId(),
        recipientId = GenerateRandom.utr(),
        uuid = UUID.randomUUID(),
        testTime = Some(Instant.now),
        content = imgWithSrcProtocol
      )
      val cleanMessage = messagesUtil.cleanUpSubjectAndContent(message).futureValue
      cleanMessage.content.get mustBe imgWithSrcProtocol
    }
    "strip the image src attribute in the message content when no protocol is present" in {
      val imgNoSrcProtocol =
        "<img src=\"www.tax.service.gov.uk/assets/4.8.0/images/direct-debit-logo.png\" alt=\"Direct Debit logo\"/>"
      val message = MessageFixtures.testMessageWithContent(
        id = new ObjectId(),
        recipientId = GenerateRandom.utr(),
        uuid = UUID.randomUUID(),
        testTime = Some(Instant.now),
        content = imgNoSrcProtocol
      )
      val cleanMessage = messagesUtil.cleanUpSubjectAndContent(message).futureValue
      cleanMessage.content.get mustBe "<img alt=\"Direct Debit logo\" />"
    }

    "not strip the details, summary and section tags for Gmc" in {
      val detailsSummaryAndSection = """<details><summary><section lang="cy"></section></summary></details>"""
      val message = MessageFixtures
        .testMessageWithoutContent(externalRef = Some(ExternalRef("id", "gmc")))
        .copy(content = Some(detailsSummaryAndSection))

      MessagesUtil.isGmc(message) mustBe true
      val cleanMessage = messagesUtil.cleanUpSubjectAndContent(message).futureValue
      cleanMessage.content.get mustBe detailsSummaryAndSection
    }

    "not strip the details and summary, but strip section tags for sources other than Gmc" in {
      val detailsSummaryAndSection = "<details><summary><section></section></summary></details>"
      val message = MessageFixtures
        .testMessageWithoutContent(externalRef = Some(ExternalRef("id", "nor-gmc")))
        .copy(content = Some(detailsSummaryAndSection))

      MessagesUtil.isGmc(message) mustBe false
      val cleanMessage = messagesUtil.cleanUpSubjectAndContent(message).futureValue
      val expectedCleanedHtml = "<details><summary></summary></details>"
      cleanMessage.content.get mustBe expectedCleanedHtml
    }
  }

  "cleanHtml" must {

    "always preserve the class attribute" in new TestCase() {
      val dirtyHtml = """<a class="foo" notpreservedattribute="bar" />"""
      val expectedResult = """<a class="foo"></a>"""
      val result = messagesUtil.cleanHtml(dirtyHtml).get
      result mustBe expectedResult
    }

    "preserve extra tag with given attribute" in new TestCase() {
      val dirtyHtml = """<section lang="cy" notpreservedattribute="bar" />"""
      val expectedResult = """<section lang="cy"></section>"""
      val result = messagesUtil.cleanHtml(dirtyHtml, List(AllowedTagAndAttributes("section", List("lang")))).get
      result mustBe expectedResult
    }
  }

  trait TestCaseHelper extends TestCase {
    reset(mockRepo, mockAuditConnector, mockPreferencesConnector, mockMessageConnector)
  }
}
