package com.ihrms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.domain.enums.UserRole;
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
 * The audit spine (§7): every SUCCESSFUL mutating request writes one append-only AuditLog row with
 * the resolved actor; failed mutations are not audited. Exercised via staff sign-in — a successful
 * {@code /auth/login} resolves the USER actor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuditMutationTest {

  private static final String STAFF_PW = "Passw0rd!";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired AuditLogRepository auditLogs;
  @Autowired PasswordEncoder encoder;
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
    User admin = staff("Ada Admin", "admin@acme.test");

    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "admin@acme.test", "password", STAFF_PW))))
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
    staff("Ada Admin", "admin@acme.test");

    // Wrong password -> 401 -> not audited.
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "admin@acme.test", "password", "wrong-password"))))
        .andExpect(status().isUnauthorized());

    assertThat(auditLogs.findByAction("POST /auth/login")).isEmpty();
  }

  private User staff(String name, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(UserRole.SUPER_ADMIN);
    u.setPasswordHash(encoder.encode(STAFF_PW)); // staff sign in with email + password (§6)
    u.setStatus("ACTIVE");
    return users.save(u);
  }
}
