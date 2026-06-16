/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.externalmessageadapter.connectors

import uk.gov.hmrc.auth.core
import uk.gov.hmrc.auth.core.AuthProvider.PrivilegedApplication
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.auth.core.{ Nino => _, _ }
import uk.gov.hmrc.domain.TaxIds.TaxIdWithName
import uk.gov.hmrc.domain._
import uk.gov.hmrc.common.message.model.TaxEntity.{ Epaye, HmceVatdecOrg, HmrcCusOrg, HmrcPptOrg }
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class AuthIdentifiersConnector @Inject() (
  val authConnector: core.AuthConnector
)(implicit ec: ExecutionContext)
    extends AuthorisedFunctions {

  def currentEffectiveTaxIdentifiers(implicit hc: HeaderCarrier): Future[Set[TaxIdWithName]] =
    currentTaxIdentifiers(hc).map { taxIds =>
      taxIds.flatMap { taxId =>
        taxId.name match {
          case "HMRC-MTD-VAT"    => Set(taxId, HmceVatdecOrg(taxId.value))
          case "HMCE-VATDEC-ORG" => Set(taxId, HmrcMtdVat(taxId.value))
          case _                 => Set(taxId)
        }
      }
    }

  def currentTaxIdentifiers(implicit hc: HeaderCarrier): Future[Set[TaxIdWithName]] =
    authorised()
      .retrieve(Retrievals.allEnrolments) { enrolments =>
        Future.successful(collectEnrolments(enrolments))
      }
      .recoverWith { case _: AuthorisationException =>
        Future.successful(Set.empty)
      }

  // scalastyle:off cyclomatic.complexity
  def collectEnrolments(enrolments: Enrolments): Set[TaxIdWithName] = enrolments.enrolments.flatMap { enrolment =>
    val taxIdValue = getIdentifierValue(enrolment)
    enrolment.key match {
      case "IR-CT"           => taxIdValue.map(CtUtr.apply)
      case "HMRC-NI"         => taxIdValue.map(Nino.apply)
      case "IR-SA"           => taxIdValue.map(SaUtr.apply)
      case "HMRC-OBTDS-ORG"  => taxIdValue.map(HmrcObtdsOrg.apply)
      case "HMRC-MTD-VAT"    => taxIdValue.map(HmrcMtdVat.apply)
      case "IR-PAYE"         => taxIdValue.map(Epaye.apply)
      case "HMCE-VATDEC-ORG" => taxIdValue.map(HmceVatdecOrg.apply)
      case "HMRC-CUS-ORG"    => taxIdValue.map(HmrcCusOrg.apply)
      case "HMRC-PPT-ORG"    => taxIdValue.map(HmrcPptOrg.apply)
      case "HMRC-MTD-IT"     => taxIdValue.map(HmrcMtdItsa.apply)
      case _                 => None
    }
  }
  // scalastyle:on

  def getIdentifierValue(enrolment: Enrolment): Option[String] =
    enrolment.identifiers match {
      case Seq(identifier) => Some(identifier.value)
      case Seq(
            EnrolmentIdentifier("TaxOfficeNumber", officeNum),
            EnrolmentIdentifier("TaxOfficeReference", officeRef)
          ) =>
        Some(EmpRef(officeNum, officeRef).value)
      case _ => None
    }

  def isStrideUser(implicit hc: HeaderCarrier): Future[Boolean] =
    authorised(AuthProviders(PrivilegedApplication))(Future.successful(true)).recoverWith {
      case _: AuthorisationException => Future.successful(false)
    }

  def strideUserId(implicit hc: HeaderCarrier): Future[Option[String]] =
    authorised(AuthProviders(PrivilegedApplication))
      .retrieve(Retrievals.credentials) {
        case Some(credentials) => Future.successful(Some(credentials.providerId))
        case None              => Future.successful(None)
      }
      .recoverWith { case _: AuthorisationException =>
        Future.successful(None)
      }

}
