package com.ihrms.offboarding.dto;

import com.ihrms.domain.enums.ClearanceFinalStatus;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
import java.util.List;
import java.util.Map;

/** DTOs for the offboarding documents + clearance (§3.6 stage 2). */
public final class OffboardingDocDtos {

  private OffboardingDocDtos() {}

  /** A form field the frontend renders generically; {@code value} is the default (HR) or prefill (employee). */
  public enum FieldKind {
    TEXT,
    DATE,
    MULTILINE
  }

  public record FieldView(String key, String label, FieldKind kind, String value, boolean required) {}

  // --- HR: send preparation + request + result ------------------------------

  /** A document HR can send, with its per-case HR fields pre-filled with defaults. {@code alreadySent} disables it. */
  public record SendableDoc(
      OffboardingDocType type, String title, boolean alreadySent, List<FieldView> hrFields) {}

  /** What the send dialog needs: the three documents with defaults + already-sent flags. */
  public record DocumentsPrepare(List<SendableDoc> documents) {}

  public record SendDocSelection(OffboardingDocType type, Map<String, String> hrValues) {}

  public record SendDocumentsRequest(List<SendDocSelection> documents) {}

  /** One document in the HR record / send result. */
  public record DocSummary(
      OffboardingDocType type,
      String title,
      OffboardingDocStatus status,
      String sentAt,
      String submittedAt,
      String verifiedAt,
      String revisionNote,
      String sentByName,
      String downloadUrl) {}

  public record SendDocumentsResult(List<DocSummary> documents) {}

  /** The record-view documents section: statuses + the send specs (for the dialog). */
  public record RecordDocuments(List<DocSummary> documents, List<SendableDoc> sendable) {}

  // --- Employee: read + complete --------------------------------------------

  public record MyDocSummary(
      OffboardingDocType type, String title, OffboardingDocStatus status, String revisionNote) {}

  /**
   * A single offboarding document to read and fill. {@code bodyHtml} is the full text with company/HR/hr_values
   * substituted (fill spots shown as prefills/blanks); {@code employeeFields} drives the fill form.
   */
  public record MyDocView(
      OffboardingDocType type,
      String title,
      OffboardingDocStatus status,
      String bodyHtml,
      String revisionNote,
      String downloadUrl,
      List<FieldView> employeeFields) {}

  public record CompleteDocRequest(
      boolean consentAccepted, Map<String, String> fillValues, String signatureDataUrl) {}

  public record CompleteDocResult(
      OffboardingDocType type, OffboardingDocStatus status, String downloadUrl) {}

  // --- HR verify / send-back ------------------------------------------------

  public record SendBackRequest(String note) {}

  // --- Clearance ------------------------------------------------------------

  public record ClearanceItemView(String key, String label, String value, String remarks) {}

  public record ClearanceSectionView(
      String key, String title, String kind, List<ClearanceItemView> items) {}

  public record ClearanceDetails(
      String name,
      String employeeId,
      String department,
      String designation,
      String manager,
      String lastWorkingDay) {}

  /** The clearance form: employee-details header (from the record), the five sections, sign-off, status. */
  public record ClearanceView(
      ClearanceDetails details,
      List<ClearanceSectionView> sections,
      String finalItSignoff,
      ClearanceFinalStatus finalStatus,
      String downloadUrl) {}

  public record ClearanceUpdateItem(String value, String remarks) {}

  public record ClearanceUpdateRequest(
      Map<String, ClearanceUpdateItem> items, String finalItSignoff, ClearanceFinalStatus finalStatus) {}

  /** A small summary for the record panel (status + download). */
  public record ClearanceSummary(ClearanceFinalStatus finalStatus, String downloadUrl) {}

  // --- Letters (§3.6 stage 3) -----------------------------------------------
  //
  // The Relieving + Experience letters are COMPANY-ISSUED: HR generates the PDF from single-source templates
  // (the employee never fills or signs them). The employee may still REQUEST a letter (a DocumentRequest
  // routed to the case HR); issuing resolves an open request, or issues directly. The upload fulfil path
  // remains as an HR fallback on a request.

  /** The gender selector on the Experience letter — drives the pronoun + title (Mr./Ms.) tokens. */
  public enum LetterGender {
    MALE,
    FEMALE
  }

  /**
   * One letter as the employee/record sees it. {@code issued} is true once HR has generated the PDF (or the
   * upload fallback fulfilled the request); {@code downloadUrl} is present whenever there is a file to
   * download (the issued PDF, or an upload-fulfilled request). {@code requestStatus} is the employee's request
   * state (null if never requested).
   */
  public record LetterView(
      com.ihrms.domain.enums.RequestType type,
      String title,
      boolean issued,
      com.ihrms.domain.enums.RequestStatus requestStatus,
      String requestedAt,
      String issuedAt,
      String note,
      String downloadUrl) {}

  /**
   * The employee's letters area. {@code gateOpen} is true only when every sent offboarding document is
   * VERIFIED — the employee can request letters only then. Issued letters appear regardless of a request.
   */
  public record LettersView(boolean gateOpen, List<LetterView> letters) {}

  public record RequestLetterRequest(String note) {}

  /**
   * One letter on the HR record's Issue panel: the prefilled field form, whether a gender selector is needed
   * (Experience), the already-issued state (+ download), and any open/resolved employee request.
   */
  public record LetterIssueSpec(
      com.ihrms.domain.enums.RequestType type,
      String title,
      boolean requiresGender,
      List<FieldView> fields,
      boolean issued,
      String issuedAt,
      String downloadUrl,
      com.ihrms.domain.enums.RequestStatus requestStatus,
      String requestNote,
      String requestedAt,
      LetterGender gender) {}

  /** The HR record's letters section: the gate state + the two letters with their issue specs. */
  public record LetterIssuePanel(boolean gateOpen, List<LetterIssueSpec> letters) {}

  /** HR issues (or previews) a letter with the typed field values (+ gender for Experience). */
  public record IssueLetterRequest(Map<String, String> hrValues, LetterGender gender) {}

  /** The substituted letter body HTML for the HR preview dialog (before issuing). */
  public record LetterPreview(String bodyHtml) {}

  // --- HR letter-requests inbox (§3.6) --------------------------------------

  /** One row in the HR's letter-requests inbox — the employee's identity + the request state. */
  public record HrLetterRequestRow(
      String id,
      String employeeId,
      String employeeCode,
      String employeeName,
      com.ihrms.domain.enums.RequestType type,
      String title,
      String note,
      com.ihrms.domain.enums.RequestStatus status,
      String requestedAt,
      String resolvedAt) {}

  /** The HR's letter-requests inbox: all letter requests routed to them + the pending count (nav badge). */
  public record HrLetterRequestsResponse(List<HrLetterRequestRow> requests, long pendingCount) {}
}
