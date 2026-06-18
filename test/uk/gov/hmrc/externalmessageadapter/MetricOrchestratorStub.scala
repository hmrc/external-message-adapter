/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter

import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator

trait MetricOrchestratorStub extends MockitoSugar {
  val mockMetricOrchestrator: MetricOrchestrator = mock[MetricOrchestrator]
}
