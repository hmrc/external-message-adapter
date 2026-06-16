/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.model

import play.api.libs.json.{ Json, Reads }

final case class Users(
  principalUserIds: List[String] = List.empty[String],
  delegatedUserIds: List[String] = List.empty[String]
)

object Users {

  implicit val reads: Reads[Users] = Json.reads[Users]

}
