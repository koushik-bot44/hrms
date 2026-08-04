package com.ihrms.agreement.dto;

import com.ihrms.domain.enums.AgreementStatus;
import com.ihrms.domain.enums.EmployeeAgreementType;
import java.util.List;

/** DTOs for the post-approval agreements flow (§Agreements). */
public final class AgreementDtos {

  private AgreementDtos() {}

  /**
   * One agreement in the HR record view / send result. {@code sentByName} is the sending HR;
   * {@code downloadUrl} is a short-lived presigned link to the rendered PDF (null until completed).
   */
  public record AgreementSummary(
      EmployeeAgreementType type,
      String title,
      AgreementStatus status,
      String sentAt,
      String completedAt,
      String sentByName,
      String downloadUrl) {}

  /** Result of HR sending the standard pack — the three PENDING agreements. */
  public record SendAgreementsResult(List<AgreementSummary> agreements) {}

  /** Prefill values for the employee fill screen; edited values are stamped into the PDF only. */
  public record AgreementPrefill(
      String fullName, String employeeCode, String designation, String address, String mobile) {}

  /**
   * The single-agreement view the employee reads and fills. {@code bodyHtml} is the full agreement text
   * with {{COMPANY_NAME}}/{{HR_NAME}} substituted and fill spots shown as blanks (the single source shared
   * with the PDF). The frontend renders the per-type fill fields from {@code prefill}.
   */
  public record MyAgreementView(
      EmployeeAgreementType type,
      String title,
      AgreementStatus status,
      String bodyHtml,
      String sentAt,
      String completedAt,
      String downloadUrl,
      AgreementPrefill prefill) {}

  /** The employee's agreement list (status + titles), newest send first not required — fixed type order. */
  public record MyAgreementSummary(
      EmployeeAgreementType type, String title, AgreementStatus status, String completedAt) {}

  /**
   * Employee's submission for one agreement. Only the fields relevant to the type are used:
   * AUP → designation + aadhaar; NDA → designation + address + mobile; Notice → none. {@code consentAccepted}
   * must be true (server-validated); {@code signatureDataUrl} is a fresh capture (same shape as onboarding).
   */
  public record CompleteAgreementRequest(
      boolean consentAccepted,
      String designation,
      String aadhaar,
      String address,
      String mobile,
      String signatureDataUrl) {}

  /** Result of completing an agreement — its now-COMPLETED state + a presigned link to the stored PDF. */
  public record CompleteAgreementResult(
      EmployeeAgreementType type, AgreementStatus status, String completedAt, String downloadUrl) {}
}
