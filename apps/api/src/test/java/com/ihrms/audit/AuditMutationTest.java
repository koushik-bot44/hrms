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
import org.springframework.test.web.servlet.MockMvc;

/**
 * The audit spine (§7): every SUCCESSFUL mutating request writes one append-only AuditLog row with
 * the resolved actor; failed mutations are not audited. Exercised via the unified OTP sign-in — a
 * successful {@code /auth/verify-otp} resolves the USER actor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuditMutationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
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
    User admin = staff("Ada Admin", "admin@acme.test");

    String otp = requestOtp("Ada Admin", "admin@acme.test");
    mvc.perform(
            post("/auth/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "admin@acme.test", "otp", otp))))
        .andExpect(status().isCreated());

    assertThat(auditLogs.findByAction("POST /auth/verify-otp"))
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

    // Wrong code -> 401 -> not audited.
    mvc.perform(
            post("/auth/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "admin@acme.test", "otp", "000000"))))
        .andExpect(status().isUnauthorized());

    assertThat(auditLogs.findByAction("POST /auth/verify-otp")).isEmpty();
  }

  private String requestOtp(String fullName, String email) throws Exception {
    var res =
        mvc.perform(
                post("/auth/request-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fullName", fullName, "email", email))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString()).get("devOtp").asText();
  }

  private User staff(String name, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(UserRole.SUPER_ADMIN);
    u.setStatus("ACTIVE"); // no password — sign-in is OTP-only
    return users.save(u);
  }
}
