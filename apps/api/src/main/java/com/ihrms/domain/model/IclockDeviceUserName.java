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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * What one terminal calls one pin (table {@code iclock_device_user_names}, V53).
 *
 * <p>Kept apart from the roster name on purpose: the roster name is what a human entered and this is
 * what the hardware believes, and the whole value of NAME DRIFT is being able to see the two
 * disagree. Stored verbatim, register junk and all.
 */
@Entity
@Table(name = "iclock_device_user_names")
@Getter
@Setter
@NoArgsConstructor
public class IclockDeviceUserName {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "deviceId", nullable = false)
  private String deviceId;

  @Column(name = "pin", nullable = false)
  private String pin;

  @Column(name = "deviceName")
  private String deviceName;

  /** Read and recorded, never echoed. This system cannot grant a device privilege. */
  @Column(name = "privilege")
  private Integer privilege;

  @Column(name = "seenAt", nullable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant seenAt = Instant.now();
}
