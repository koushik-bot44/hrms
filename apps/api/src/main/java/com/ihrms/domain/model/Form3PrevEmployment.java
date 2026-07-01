package com.ihrms.domain.model;

import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.support.CuidId;
import com.ihrms.domain.support.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

/**
 * Form 3 — Previous Employment (table {@code form3_prev_employment}; repeatable, many per employee).
 * {@code companyName} is the PREVIOUS employer (employee-entered); {@code lastDrawnSalary} is
 * encrypted at rest (§6). Dates are stored as free-form strings (YYYY-MM-DD or as entered).
 */
@Entity
@Table(name = "form3_prev_employment")
@Getter
@Setter
@NoArgsConstructor
public class Form3PrevEmployment {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Column(name = "orderIndex", nullable = false)
  private int orderIndex;

  @Column(name = "companyName")
  private String companyName;

  @Column(name = "companyAddress")
  private String companyAddress;

  @Column(name = "dateOfJoining")
  private String dateOfJoining;

  @Column(name = "dateOfRelieving")
  private String dateOfRelieving;

  @Column(name = "designation")
  private String designation;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "lastDrawnSalary")
  private String lastDrawnSalary;

  @Column(name = "jobType")
  private String jobType;

  @Column(name = "reasonForLeaving")
  private String reasonForLeaving;

  @Column(name = "reportingTo")
  private String reportingTo;

  @Column(name = "roContact")
  private String roContact;

  @Column(name = "hrNameContact")
  private String hrNameContact;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private SectionStatus status = SectionStatus.DRAFT;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
