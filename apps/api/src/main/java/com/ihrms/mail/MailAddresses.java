package com.ihrms.mail;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Forms and validates internal-mail addresses (§8). A staff mailbox address is {@code localpart@domain}
 * and IS the user's login email (single identity). At provisioning the assigner types the local part;
 * this forms the address — the single, only way an address/login email is created (the earlier
 * transitional {@code email} fallback has been retired now the UI always sends a local part).
 */
@Component
public class MailAddresses {

  /** The platform mail domain for the top-level roles (SUPER_ADMIN, ACCOUNTS_ADMIN). */
  public static final String PLATFORM_DOMAIN = "ihrms";

  private static final Pattern LOCAL_PART = Pattern.compile("[a-z0-9]([a-z0-9._-]*[a-z0-9])?");

  /**
   * A mail domain: one or more dot-separated labels, each lowercase alphanumeric with internal hyphens,
   * no leading/trailing '-' or '.' (e.g. {@code acme}, {@code acme-corp}, {@code yourdomain.com}). Shared
   * with the create-company request validation (the DTO's {@code @Pattern}).
   */
  public static final String DOMAIN_REGEX =
      "[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*";

  private static final Pattern DOMAIN = Pattern.compile(DOMAIN_REGEX);

  /** A resolved mailbox address: the full {@code email} plus its {@code localPart}. */
  public record Address(String email, String localPart) {}

  /**
   * Resolve the mailbox from a typed {@code localPart} + a domain: {@code localpart@domain}, which IS
   * the login email. The local part is required (400 if blank). Uniqueness within the domain is enforced
   * downstream by the global email-unique index (the address IS the email).
   */
  public Address resolve(String localPart, String domain) {
    if (!isPresent(localPart)) {
      throw badRequest("Provide a mailbox local part");
    }
    String lp = normalizeLocalPart(localPart);
    return new Address(lp + "@" + normalizeDomain(domain), lp);
  }

  public String normalizeLocalPart(String localPart) {
    String lp = localPart == null ? "" : localPart.trim().toLowerCase();
    if (!LOCAL_PART.matcher(lp).matches()) {
      throw badRequest("Mailbox name must be letters, digits, dot, underscore or hyphen — no spaces or @");
    }
    return lp;
  }

  public String normalizeDomain(String domain) {
    String d = domain == null ? "" : domain.trim().toLowerCase();
    if (!DOMAIN.matcher(d).matches()) {
      throw badRequest("Mail domain must be lowercase letters, digits, hyphen or dot — no spaces or @");
    }
    return d;
  }

  private static boolean isPresent(String s) {
    return s != null && !s.isBlank();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
