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
}
