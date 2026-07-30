package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.repository.AuditLogRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Super-Admin company management (contract §3.3): CRUD, duplicate code, admin provisioning, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class CompaniesApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String superToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("super-1", "super@x.test", "Super", UserRole.SUPER_ADMIN, null, null));
  }

  @Test
  void createsListsAndGetsCompany() throws Exception {
    MvcResult created =
        mvc.perform(asSuper(post("/companies"), Map.of("name", "Acme Inc", "code", "acme")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode detail = json.readTree(created.getResponse().getContentAsString());
    String id = detail.get("id").asText();
    assertThat(detail.get("name").asText()).isEqualTo("Acme Inc");
    assertThat(detail.get("code").asText()).isEqualTo("ACME"); // upper-cased
    assertThat(detail.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(detail.get("teamCount").asInt()).isZero();
    assertThat(detail.get("employeeCount").asInt()).isZero();
    assertThat(detail.get("hasAdmin").asBoolean()).isFalse();
    assertThat(detail.get("admin").isNull()).isTrue();

    MvcResult list =
        mvc.perform(get("/companies").header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode arr = json.readTree(list.getResponse().getContentAsString());
    assertThat(arr.isArray()).isTrue();
    assertThat(arr.get(0).get("id").asText()).isEqualTo(id);

    mvc.perform(get("/companies/" + id).header("Authorization", "Bearer " + superToken))
        .andExpect(status().isOk());

    // Audit row partitioned under the new company.
    assertThat(auditLogs.findByAction("COMPANY_CREATED"))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getCompanyId()).isEqualTo(id);
              assertThat(row.getActorType()).isEqualTo("USER");
              assertThat(row.getActorId()).isEqualTo("super-1");
            });
  }

  @Test
  void duplicateCodeIsConflict() throws Exception {
    mvc.perform(asSuper(post("/companies"), Map.of("name", "Acme", "code", "ACME")))
        .andExpect(status().isCreated());
    MvcResult dup =
        mvc.perform(asSuper(post("/companies"), Map.of("name", "Acme Two", "code", "acme")))
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(json.readTree(dup.getResponse().getContentAsString()).get("statusCode").asInt())
        .isEqualTo(409);
  }

  @Test
  void updatesNameAndStatus() throws Exception {
    String id = createCompany("Acme", "ACME");
    MvcResult res =
        mvc.perform(asSuper(patch("/companies/" + id), Map.of("status", "SUSPENDED")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(res.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("SUSPENDED");
  }

  @Test
  void provisionsAdminOnceThenConflicts() throws Exception {
    String id = createCompany("Acme", "ACME");

    MvcResult res =
        mvc.perform(asSuper(post("/companies/" + id + "/admin"),
                Map.of("name", "Ada Admin", "localPart", "ada", "password", "AdaAdmin@1")))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    // localPart@companyDomain (code ACME -> domain "acme") IS the login email (§8).
    assertThat(body.get("admin").get("email").asText()).isEqualTo("ada@acme");
    assertThat(body.get("admin").get("status").asText()).isEqualTo("ACTIVE");
    // The initial password is echoed in dev (the admin who set it already knows it).
    assertThat(body.get("devPassword").asText()).isEqualTo("AdaAdmin@1");

    // The provisioned admin can sign in with email + password and is a COMPANY_ADMIN scoped to
    // the company.
    MvcResult login =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                        Map.of("email", "ada@acme", "password", "AdaAdmin@1"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode session = json.readTree(login.getResponse().getContentAsString()).get("session");
    assertThat(session.get("role").asText()).isEqualTo("COMPANY_ADMIN");
    assertThat(session.get("companyId").asText()).isEqualTo(id);
    // Stage 2: the session carries the company's URL slug (code ACME -> name "Acme" -> slug "acme").
    assertThat(session.get("companySlug").asText()).isEqualTo("acme");

    // hasAdmin now true.
    MvcResult detail =
        mvc.perform(get("/companies/" + id).header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(detail.getResponse().getContentAsString()).get("hasAdmin").asBoolean())
        .isTrue();

    // Second admin (any mailbox) rejected — one admin per company.
    mvc.perform(asSuper(post("/companies/" + id + "/admin"),
            Map.of("name", "Bob", "localPart", "bob", "password", "BobAdmin@1")))
        .andExpect(status().isConflict());
  }

  @Test
  void nonSuperAdminIsForbidden() throws Exception {
    String hrToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("hr-1", "hr@x.test", "HR", UserRole.HR, "company-1", null));
    mvc.perform(get("/companies").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
  }

  // --- helpers --------------------------------------------------------------

  private String createCompany(String name, String code) throws Exception {
    MvcResult res =
        mvc.perform(asSuper(post("/companies"), Map.of("name", name, "code", code)))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
  }

  private MockHttpServletRequestBuilder asSuper(MockHttpServletRequestBuilder builder, Object body)
      throws Exception {
    return builder
        .header("Authorization", "Bearer " + superToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }
}
