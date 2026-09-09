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
import org.hibernate.type.SqlTypes;

/**
 * One queued instruction for one terminal (table {@code iclock_device_commands}, Flyway V50).
 *
 * <p>The first server-to-device write path in this system. Everything before it only read from the
 * terminals; a command changes what is on a device's screen and in its user table, so every row here
 * names who asked for it and how many times it has been served.
 */
@Entity
@Table(name = "iclock_device_commands")
@Getter
@Setter
@NoArgsConstructor
public class IclockDeviceCommand {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "deviceId", nullable = false)
  private String deviceId;

  /** UPDATE_USERINFO, SET_TIME or DELETE_USER. */
  @Column(name = "kind", nullable = false)
  private String kind;

  /**
   * The exact bytes served, minus the {@code C:<id>:} prefix which is applied at serve time.
   *
   * <p>Stored verbatim because when a terminal does something surprising the only useful question is
   * what precisely was sent to it.
   */
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "status", nullable = false)
  private String status = "PENDING";

  /** Times handed to the device. The cap is what stops an un-acked command being served forever. */
  @Column(name = "serveCount", nullable = false)
  private int serveCount;

  /** Whatever the device sent back, in whatever shape. The ack format is unproven on this fleet. */
  @Column(name = "ackRaw")
  private String ackRaw;

  @Column(name = "ackReturn")
  private String ackReturn;

  @Column(name = "failureReason")
  private String failureReason;

  @Column(name = "personId")
  private String personId;

  @Column(name = "devicePin")
  private String devicePin;

  @Column(name = "createdBy", nullable = false)
  private String createdBy;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "sentAt")
  private Instant sentAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "completedAt")
  private Instant completedAt;
}
