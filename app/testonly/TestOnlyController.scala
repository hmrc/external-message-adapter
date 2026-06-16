/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package testonly

import play.api.libs.json.Json
import play.api.mvc.{ Action, AnyContent, ControllerComponents }
import uk.gov.hmrc.externalmessageadapter.repository.MessageAdminRepository
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext

@Singleton
class TestOnlyController @Inject() (messageAdminRepository: MessageAdminRepository, cc: ControllerComponents)(implicit
  ec: ExecutionContext
) extends BackendController(cc) {

  def deleteMessages(): Action[AnyContent] = Action.async {
    messageAdminRepository.deleteAll().map { n =>
      Ok(Json.obj("deleted" -> n))
    }
  }

}
