package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A person on the biometric roster (table {@code iclock_people}, Flyway V44) — the SOLE source of pin
 * resolution.
 *
 * <p><b>Identity-first.</b> P1a required an IHRMS {@code employees} row before a punch could be
 * attributed, which made the system unusable against a 206-person terminal roster and 25 onboarded
 * employees. The roster is now primary and owns identity; {@link #employeeId} is deferred ENRICHMENT
 * that arrives as onboarding catches up. A person with no employee link is fully functional — they
 * appear on the board, their punches collapse into bursts, their day view works.
 *
 * <p>{@link #email} is nullable and deliberately NOT unique: the real seed data contains one address
 * shared by two different people. Duplicates are flagged in the console, never blocking. {@link #name}
 * is nullable because a person seeded from punch history alone genuinely has no name, and inventing a
 * placeholder would be worse than showing "unnamed" to an operator who can fix it.
 */
@Entity
@Table(name = "iclock_people")
@Getter
@Setter
@NoArgsConstructor
public class IclockPerson {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "siteId", nullable = false)
  private String siteId;

  /** Canonical: digits, no leading zeros. Unique within the site. */
  @Column(name = "pin", nullable = false)
  private String pin;

  @Column(name = "name")
  private String name;

  /** Not unique — see the class javadoc. */
  @Column(name = "email")
  private String email;

  /** Resolved IHRMS company, when the import's label matched one. */
  @Column(name = "companyId")
  private String companyId;

  /**
   * The import's company label verbatim, kept whether or not it resolved. An unmatched label is
   * information, not noise: it says a company exists on the terminals but not in IHRMS.
   */
  @Column(name = "companyLabel")
  private String companyLabel;

  @Column(name = "team")
  private String team;

  @Column(name = "role")
  private String role;

  /** P2 policy input. Carried through the import and stored; NOT applied by the P1b pipeline. */
  @Column(name = "lateExemptMin", nullable = false)
  private int lateExemptMin;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  /**
   * Excluded from P2 reporting and warning mail — NOT from attendance.
   *
   * <p>Orthogonal to {@link #active}: this person resolves, promotes and shows on the live board and
   * day view exactly like anyone else. The legacy tool's "deleted" flag meant report-exclusion rather
   * than departure, and treating the two as the same thing would make current employees vanish from
   * the floor. Behaviourally inert in P1b — captured and surfaced, never consulted by the pipeline.
   */
  @Column(name = "excludedFromReports", nullable = false)
  private boolean excludedFromReports;

  /**
   * Which shift this person works — {@code NIGHT} (19:00→04:00) or {@code DAY} (09:00→18:00), V47.
   *
   * <p><b>Their shift-day cut derives from this</b>, along with their late threshold, their break-alert
   * window and which day the board and Missing OUT read for them. A global constant was correct while
   * everyone worked nights and silently mis-filed a day-shift person's every punch once they did not:
   * a 09:00 arrival lands one shift-day earlier than the 18:00 departure that follows it, so the pair
   * never closes.
   *
   * <p>Defaults to {@code NIGHT} — which is exactly current behaviour — and only ever moves by an
   * explicit, audited assignment. Never inferred from punch times: someone newly enrolled has a punch
   * history made of onboarding noise, and guessing from it would re-date attribution that works.
   */
  @Column(name = "shiftProfile", nullable = false)
  private String shiftProfile = "NIGHT";

  /** Deferred enrichment: the IHRMS employee this person turned out to be. Null until confirmed. */
  @Column(name = "employeeId")
  private String employeeId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
