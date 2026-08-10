package com.ihrms.contact.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public contact-form DTOs (§8d). Nothing here is persisted — the submission is emailed and discarded. The
 * honeypot ({@code website}) and {@code elapsedMs} are anti-spam signals, not real fields; a bot that trips
 * either gets a silent success (no email). All copy stays in the confidential register — no internal vocabulary.
 */
public final class ContactDtos {

  private ContactDtos() {}

  public record ContactRequest(
      @NotBlank(message = "Name is required")
          @Size(min = 2, max = 80, message = "Name must be 2–80 characters")
          String name,
      @NotBlank(message = "Work email is required")
          @Email(message = "Enter a valid email")
          @Size(max = 120, message = "Email is too long")
          String email,
      @NotBlank(message = "Organization is required")
          @Size(min = 2, max = 120, message = "Organization must be 2–120 characters")
          String organization,
      @Size(max = 2000, message = "Message is too long (2000 characters max)") String message,
      /** Honeypot: real people never see this; if non-empty the submission is silently dropped. */
      String website,
      /** Client-measured time-on-page (ms); a submit under ~3s is treated as a bot and silently dropped. */
      Long elapsedMs) {}

  /** Always {@code {ok:true}} on a 2xx — a genuine send and a silent bot-drop look identical to the client. */
  public record ContactResult(boolean ok) {}
}
