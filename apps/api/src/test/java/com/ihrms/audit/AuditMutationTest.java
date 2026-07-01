package com.ihrms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The audit spine (§7): every SUCCESSFUL mutating request writes one append-only AuditLog
 * row with the resolved actor; failed mutations are not audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuditMutationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
  }

  @Test
  void successfulMutationWritesAuditRowWithActor() throws Exception {
    User admin = staff("admin@acme.test", "Password@123");

    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("email", "admin@acme.test", "password", "Password@123"))))
        .andExpect(status().isCreated());

    assertThat(auditLogs.findByAction("POST /auth/login"))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getActorType()).isEqualTo("USER");
              assertThat(row.getActorId()).isEqualTo(admin.getId());
              assertThat(((Number) row.getMetadata().get("statusCode")).intValue()).isEqualTo(201);
            });
  }

  @Test
  void failedMutationIsNotAudited() throws Exception {
    staff("admin@acme.test", "Password@123");

    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("email", "admin@acme.test", "password", "WrongPass@1"))))
        .andExpect(status().isUnauthorized());

    assertThat(auditLogs.findByAction("POST /auth/login")).isEmpty();
  }

  private User staff(String email, String password) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(UserRole.SUPER_ADMIN);
    u.setPasswordHash(encoder.encode(password));
    u.setStatus("ACTIVE");
    return users.save(u);
  }
}
