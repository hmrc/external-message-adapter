/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.util

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }

trait WithWireMock extends BeforeAndAfterAll with BeforeAndAfterEach { suite: Suite =>

  def dependenciesPort = 5808
  lazy val wireMockServer = new WireMockServer(wireMockConfig().port(dependenciesPort))

  override def beforeAll(): Unit = {
    super.beforeAll()
    wireMockServer.start()
    WireMock.configureFor(dependenciesPort)
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    wireMockServer.resetMappings()
    wireMockServer.resetRequests()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    wireMockServer.stop()
  }

}
