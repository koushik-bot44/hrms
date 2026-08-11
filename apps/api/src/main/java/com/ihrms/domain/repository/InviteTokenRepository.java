package com.ihrms.domain.repository;

import com.ihrms.domain.model.InviteToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteTokenRepository extends JpaRepository<InviteToken, String> {

  /** Resolve a presented token by its SHA-256 hex (the unique lookup key). */
  Optional<InviteToken> findByTokenHash(String tokenHash);

  /** The employee's not-yet-revoked tokens — revoked when a fresh one is issued (one-active invariant). */
  List<InviteToken> findByEmployeeIdAndRevokedAtIsNull(String employeeId);

  /** The most recent not-revoked token for an employee — its {@code createdAt} is "invite last sent". */
  Optional<InviteToken> findFirstByEmployeeIdAndRevokedAtIsNullOrderByCreatedAtDesc(String employeeId);
}
