package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One pin a terminal reported holding, and what the roster says about it (V52).
 *
 * <p>NAME DRIFT is deliberately not a verdict here. This firmware never sends a name, so a
 * device-vs-roster name comparison has no input on this fleet; pushing a corrected name stays
 * available per person, it simply cannot be a finding.
 */
@Entity
@Table(name = "iclock_register_entries")
@Getter
@Setter
@NoArgsConstructor
public class IclockRegisterEntry {

  /** Active roster row at this building. */
  public static final String CLEAN = "CLEAN";

  /** Roster row here, but deactivated — the register is holding somebody who has left. */
  public static final String STALE = "STALE";

  /** Never rostered here. Register debris, or somebody nobody has added yet. */
  public static final String UNKNOWN = "UNKNOWN";

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "auditId", nullable = false)
  private String auditId;

  @Column(name = "pin", nullable = false)
  private String pin;

  @Column(name = "fingerCount", nullable = false)
  private int fingerCount;

  /** Comma-separated FIDs, so a partial enrolment is legible without a second query. */
  @Column(name = "fingerIndexes")
  private String fingerIndexes;

  @Column(name = "hasFace", nullable = false)
  private boolean hasFace;

  @Column(name = "hasPhoto", nullable = false)
  private boolean hasPhoto;

  @Column(name = "verdict", nullable = false)
  private String verdict = UNKNOWN;

  @Column(name = "personId")
  private String personId;

  @Column(name = "personName")
  private String personName;
}
