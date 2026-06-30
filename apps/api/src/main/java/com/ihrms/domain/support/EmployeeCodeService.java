package com.ihrms.domain.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Allocates per-company employee-ID sequences ATOMICALLY (§5). A single
 * {@code INSERT … ON CONFLICT ("companyId") DO UPDATE SET "lastSeq" = "lastSeq" + 1
 * RETURNING} (the same statement the archived NestJS API used) is concurrency-safe and
 * gap-tolerant — parallel onboards never collide.
 */
@Service
public class EmployeeCodeService {

  private static final String ALLOCATE_SQL =
      """
      INSERT INTO employee_code_sequences ("id", "companyId", "lastSeq", "createdAt", "updatedAt")
      VALUES (?, ?, 1, now(), now())
      ON CONFLICT ("companyId") DO UPDATE
        SET "lastSeq" = employee_code_sequences."lastSeq" + 1, "updatedAt" = now()
      RETURNING "lastSeq"
      """;

  private final JdbcTemplate jdbc;

  public EmployeeCodeService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Atomically allocate and return the next sequence number for a company. */
  public int allocateSequence(String companyId) {
    Integer seq = jdbc.queryForObject(ALLOCATE_SQL, Integer.class, Cuids.newId(), companyId);
    return seq == null ? 0 : seq;
  }

  /** Allocate the next full employee code for a company. */
  public String nextCode(String companyId, String companyCode) {
    return EmployeeCodes.format(companyCode, allocateSequence(companyId));
  }
}
