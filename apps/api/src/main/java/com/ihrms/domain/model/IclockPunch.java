package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * An effective, attributed punch (table {@code iclock_punches}, Flyway V43) — one row per BURST, not
 * per raw punch. Survivors of collapse only; {@code iclock_raw_punches} stays complete and unmutated.
 *
 * <p><b>Anchoring.</b> The row is anchored on {@link #rawPunchId} = the FIRST raw punch of the burst,
 * and that anchor never moves. {@link #effectiveRawPunchId} and {@link #effectiveAt} carry the KEPT
 * punch, which for an OUT burst moves forward as the burst extends. Anchoring on the first punch is
 * what lets {@code rawPunchId} stay UNIQUE — the idempotency hook — while the effective instant is
 * still updated live, giving immediate visibility with no settling delay.
 *
 * <p><b>Direction is never inferred from the payload.</b> On this fleet {@code statusCode} is always
 * 255 and {@code verifyMode} always 15, so {@link #direction} comes solely from the claimed device's
 * configured role.
 */
@Entity
@Table(name = "iclock_punches")
@Getter
@Setter
@NoArgsConstructor
public class IclockPunch {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** Idempotency anchor: the burst's FIRST raw punch. Uniquely indexed; never reassigned. */
  @Column(name = "rawPunchId", nullable = false)
  private String rawPunchId;

  /** The punch whose timestamp is authoritative: the anchor for IN, the latest for OUT. */
  @Column(name = "effectiveRawPunchId", nullable = false)
  private String effectiveRawPunchId;

  @Column(name = "deviceId", nullable = false)
  private String deviceId;

  @Column(name = "siteId", nullable = false)
  private String siteId;

  /**
   * The roster person this punch belongs to — the primary identity from P1b onward (V44).
   *
   * <p>Nullable in the SCHEMA only to survive a rolling deploy where the P1a jar, which does not know
   * this column, briefly still writes rows. Every punch the P1b pipeline promotes sets it.
   */
  @Column(name = "personId")
  private String personId;

  /**
   * Snapshotted IHRMS company at promotion. NULLABLE since V44: a roster person whose company label
   * never matched an IHRMS company still punches, and refusing to record that would lose real
   * attendance over a bookkeeping gap.
   */
  @Column(name = "companyId")
  private String companyId;

  /**
   * The linked IHRMS employee, when there is one. NULLABLE since V44 — under the identity-first model
   * most people have no employee record yet, and that must not stop their punches being attributed.
   */
  @Column(name = "employeeId")
  private String employeeId;

  /** Canonical pin (leading zeros stripped), as resolved. */
  @Column(name = "devicePin", nullable = false)
  private String devicePin;

  /** The anchor punch's instant, interpreted in the site's timezone. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "punchedAt", nullable = false)
  private Instant punchedAt;

  /** The authoritative instant: equals {@link #punchedAt} for IN, the burst's last punch for OUT. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "effectiveAt", nullable = false)
  private Instant effectiveAt;

  /**
   * Shift-day of the KEPT punch — derived from {@link #effectiveAt}, never from the anchor, so a burst
   * straddling the 04:00 shift-day cut is attributed to the day it actually counts for.
   */
  @Column(name = "shiftDate", nullable = false)
  private LocalDate shiftDate;

  @Column(name = "area", nullable = false)
  private String area;

  @Column(name = "direction", nullable = false)
  private String direction;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "burstFirstAt", nullable = false)
  private Instant burstFirstAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "burstLastAt", nullable = false)
  private Instant burstLastAt;

  /**
   * DISTINCT RAW LINES in the burst — not the number of times the person presented. V42's content
   * dedupe already collapses byte-identical same-second repeats, so a genuine double-tap inside one
   * second is invisible here by design.
   */
  @Column(name = "burstCount", nullable = false)
  private int burstCount = 1;

  /** Surfaced, never silently resolved. Null = clean. */
  @Column(name = "anomaly")
  private String anomaly;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
