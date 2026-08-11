package com.ihrms.onboarding;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.InviteToken;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.InviteTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues + validates the opaque invite token that gates the employee onboarding door (§3.2/§6). The token is a
 * cryptographically-random value (NOT a JWT — revocable); only its SHA-256 hex is stored, and lookup is BY that
 * hash (BCrypt's per-row salt can't be looked up by value — hence a deterministic hash for this high-entropy
 * secret). One ACTIVE token per employee: {@link #issueFor} revokes any prior un-revoked token before inserting
 * the new one, so a resend / re-invite kills the old link. A token authorizes the door only while it is not
 * revoked, not expired, AND the employee is still in an active onboarding status.
 */
@Service
public class InviteTokenService {

  /** Invite links live for 7 days from issue (§3.2). */
  private static final Duration TTL = Duration.ofDays(7);

  /**
   * Statuses in which an invite token still authorizes the onboarding door. Once the employee is APPROVED /
   * REJECTED / OFFBOARDED the onboarding is over (they move to workspace credentials) and the token is dead.
   */
  private static final Set<EmployeeStatus> ONBOARDING_ACTIVE =
      EnumSet.of(
          EmployeeStatus.INVITED,
          EmployeeStatus.IN_PROGRESS,
          EmployeeStatus.SUBMITTED,
          EmployeeStatus.REVISION_REQUESTED,
          EmployeeStatus.HR_VERIFIED);

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

  /** An active token bound to a still-onboarding employee (what the door + OTP endpoints resolve to). */
  public record ResolvedInvite(InviteToken token, Employee employee) {}

  private final InviteTokenRepository tokens;
  private final EmployeeRepository employees;

  public InviteTokenService(InviteTokenRepository tokens, EmployeeRepository employees) {
    this.tokens = tokens;
    this.employees = employees;
  }

  /**
   * Issue a fresh invite token for an employee, revoking any prior un-revoked token first (one-active
   * invariant). Returns the RAW token to embed in the link — it is never stored (only its hash is).
   */
  @Transactional
  public String issueFor(String employeeId) {
    Instant now = Instant.now();
    for (InviteToken prior : tokens.findByEmployeeIdAndRevokedAtIsNull(employeeId)) {
      prior.setRevokedAt(now);
      tokens.save(prior);
    }
    String raw = URL64.encodeToString(randomBytes());
    InviteToken token = new InviteToken();
    token.setEmployeeId(employeeId);
    token.setTokenHash(sha256Hex(raw));
    token.setExpiresAt(now.plus(TTL));
    tokens.save(token);
    return raw;
  }

  /**
   * Resolve a presented raw token to its active invite + still-onboarding employee, or empty for
   * missing/unknown/revoked/expired tokens or an employee past onboarding. Callers treat empty as a single
   * generic failure (no distinction between the reasons — anti-enumeration, §6).
   */
  @Transactional(readOnly = true)
  public Optional<ResolvedInvite> resolve(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    return tokens
        .findByTokenHash(sha256Hex(rawToken.trim()))
        .filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(Instant.now()))
        .flatMap(
            t ->
                employees
                    .findById(t.getEmployeeId())
                    .filter(e -> ONBOARDING_ACTIVE.contains(e.getStatus()))
                    .map(e -> new ResolvedInvite(t, e)));
  }

  /** Stamp first use (informational — never gates validity). No-op if already used. */
  @Transactional
  public void markUsed(InviteToken token) {
    if (token.getUsedAt() == null) {
      token.setUsedAt(Instant.now());
      tokens.save(token);
    }
  }

  /** "Invite last sent" for the HR record view — the newest un-revoked token's {@code createdAt}, or null. */
  @Transactional(readOnly = true)
  public Instant sentAt(String employeeId) {
    return tokens
        .findFirstByEmployeeIdAndRevokedAtIsNullOrderByCreatedAtDesc(employeeId)
        .map(InviteToken::getCreatedAt)
        .orElse(null);
  }

  private static byte[] randomBytes() {
    byte[] bytes = new byte[32]; // 256 bits of entropy
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /** Deterministic SHA-256 hex — safe for a high-entropy random token (no brute-force surface like a password). */
  static String sha256Hex(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
    }
  }
}
