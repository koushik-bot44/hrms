package com.ihrms.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Web Push request/response contracts (§ Web Push, Stage N1). */
public final class PushDtos {

  private PushDtos() {}

  @Schema(description = "The VAPID public key the browser needs to create a PushSubscription.")
  public record PublicKeyResponse(
      @Schema(description = "VAPID public key (base64url); empty when push is not configured.")
          String publicKey,
      @Schema(description = "Whether the server has Web Push configured (VAPID keys present).")
          boolean enabled) {}

  @Schema(description = "A browser PushSubscription to register for the acting principal.")
  public record SubscribeRequest(
      @Schema(description = "Push service endpoint URL (unique per browser/device).")
          @NotBlank(message = "endpoint is required")
          String endpoint,
      @NotNull(message = "keys are required") @Valid Keys keys,
      @Schema(description = "Optional user-agent label for the device.") String userAgent) {

    @Schema(description = "The subscription's encryption keys (from PushSubscription.toJSON()).")
    public record Keys(
        @Schema(description = "Client public key (base64url).") @NotBlank(message = "p256dh is required")
            String p256dh,
        @Schema(description = "Client auth secret (base64url).") @NotBlank(message = "auth is required")
            String auth) {}
  }

  @Schema(description = "Result of registering a subscription.")
  public record SubscribeResult(
      @Schema(description = "Server id of the stored subscription.") String id,
      @Schema(description = "True if this endpoint was already registered and was updated.")
          boolean existing) {}

  @Schema(description = "Remove a previously registered subscription (by endpoint).")
  public record UnsubscribeRequest(
      @NotBlank(message = "endpoint is required") String endpoint) {}

  @Schema(description = "Result of a test send to the caller's own subscriptions.")
  public record TestResult(
      @Schema(description = "How many of the caller's subscriptions were targeted.") int targeted,
      @Schema(description = "Human-readable status (e.g. push disabled, or sent).") String message) {}
}
