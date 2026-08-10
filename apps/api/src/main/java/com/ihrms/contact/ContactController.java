package com.ihrms.contact;

import com.ihrms.contact.dto.ContactDtos.ContactRequest;
import com.ihrms.contact.dto.ContactDtos.ContactResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The PUBLIC, UNAUTHENTICATED contact endpoint (§8d) — the one marketing path that reaches the API. A
 * permitAll carve-out in {@code SecurityConfig} exposes exactly {@code POST /public/contact}; the body is
 * bean-validated (→ 400 with field errors). {@link ContactService} applies the anti-spam + rate limit and
 * emails the submission (Reply-To = submitter). Nothing is stored. A transport failure is reported as a
 * GENERIC error — internals are never leaked.
 */
@RestController
public class ContactController {

  private final ContactService contact;

  public ContactController(ContactService contact) {
    this.contact = contact;
  }

  @PostMapping("/public/contact")
  public ContactResult submit(@Valid @RequestBody ContactRequest body, HttpServletRequest request) {
    try {
      contact.submit(body, request.getRemoteAddr());
    } catch (ResponseStatusException e) {
      throw e; // 429 rate limit — friendly message already set
    } catch (Exception e) {
      // Never surface SMTP/internal detail to the visitor.
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "We couldn't send your message right now — please try again, or email info@hrorg.in");
    }
    return new ContactResult(true);
  }
}
