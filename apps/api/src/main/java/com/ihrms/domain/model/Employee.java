package com.ihrms.domain.model;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** The onboarded subject (table {@code employees}). Auth = employeeCode + email + OTP (§6). */
@Entity
@Table(name = "employees")
@Getter
@Setter
@NoArgsConstructor
public class Employee {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeCode", nullable = false)
  private String employeeCode;

  @Column(name = "email", nullable = false)
  private String email;

  @Column(name = "companyId", nullable = false)
  private String companyId;

  @Column(name = "onboardingHrId", nullable = false)
  private String onboardingHrId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private EmployeeStatus status = EmployeeStatus.INVITED;

  @Column(name = "otpHash")
  private String otpHash;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "otpExpiresAt")
  private Instant otpExpiresAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
