package com.ihrms.onboarding.dto;

import com.ihrms.domain.enums.OfferStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The Offer Letter (§3.2) — the company-issued letter that opens onboarding. HR supplies the terms at invite
 * (salary + location; joining date / designation / name are reused from the Form-2 onboard payload); the
 * invited employee reads, signs and ACCEPTS it before any form unlocks.
 */
public final class OfferDtos {

  private OfferDtos() {}

  /**
   * The offer terms HR types at invite. {@code joiningDate}, designation and name are NOT here — they are
   * reused from the Form-2 onboard payload (do not duplicate). {@code location} defaults to Hyderabad.
   */
  public record OfferTermsRequest(
      @NotBlank(message = "Salary is required") @Size(max = 200) String salary,
      @Size(max = 120) String location) {}

  /** The invited employee accepts the offer — consent + a fresh acceptance signature. */
  public record AcceptOfferRequest(boolean consentAccepted, String signatureDataUrl) {}

  /**
   * The employee's own offer screen. {@code bodyHtml} is the full offer text with the terms substituted (the
   * signature slot is empty on the read view); {@code downloadUrl} is the accepted PDF (null until accepted).
   */
  public record MyOfferView(
      OfferStatus status,
      String bodyHtml,
      String employeeName,
      String offerDate,
      String acceptedAt,
      String downloadUrl) {}

  /** The lightweight offer state carried on the onboarding dashboard (drives the portal's offer gate). */
  public record OfferSummary(OfferStatus status, String acceptedAt, String downloadUrl) {}

  /**
   * The offer state on the HR record panel — status + dates only (NO salary, NO download URL). The PDF (which
   * carries the salary) is fetched via the dedicated role-gated endpoint, not embedded in the shared record.
   */
  public record OfferRecordView(OfferStatus status, String offerDate, String acceptedAt) {}
}
