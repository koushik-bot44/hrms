package com.ihrms.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HIERARCHY role (ARCHITECTURE.md §2/§6): a SUPER_ADMIN-provisioned SINGLETON, cross-platform,
 * READ-ONLY, AGGREGATES-ONLY overview principal ({@code companyId = null}) — mirrors the Accounts Admin
 * provisioning. Covers: singleton provisioning + email/password login + audit; remove/replace; and the
 * authorization boundary — HIERARCHY cannot write, cannot read an individual record/PII, cannot reach
 * attendance/leave or the read-only viewer area, and its {@code /hierarchy/**} namespace is role-gated.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class HierarchyApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired UserRepository users;
  @Autowired CompanyRepository companies;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String superToken;
  private String companyId;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"audit_logs\" RESTART IDENTITY CASCADE");
    companyId = company("AAA");
    User sa = user(null, UserRole.SUPER_ADMIN, "super@x.test");
    superToken = token(sa, UserRole.SUPER_ADMIN);
  }

  @Test
  void superAdminProvisionsTheSingleHierarchyAndASecondIsRejected() throws Exception {
    assertThat(provisioningStatus(superToken).get("exists").asBoolean()).isFalse();

    MvcResult created =
        mvc.perform(
                post("/provisioning/hierarchy")
                    .header("Authorization", "Bearer " + superToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                        Map.of("name", "Harper Vue", "localPart", "Hank", "password", "Overseer@2026"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = json.readTree(created.getResponse().getContentAsString());
    // localPart@ihrms (the platform domain) IS the login email, lower-cased (§8).
    assertThat(body.get("hierarchy").get("email").asText()).isEqualTo("hank@ihrms");
    assertThat(body.get("devPassword").asText()).isEqualTo("Overseer@2026"); // echoed in dev
    assertThat(users.existsByRole(UserRole.HIERARCHY)).isTrue();
    assertThat(provisioningStatus(superToken).get("exists").asBoolean()).isTrue();

    // Singleton: a second create is rejected.
    mvc.perform(
            post("/provisioning/hierarchy")
                .header("Authorization", "Bearer " + superToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", "Second One", "localPart", "second", "password", "Another@2026"))))
        .andExpect(status().isConflict());
    assertThat(auditLogs.findByAction("HIERARCHY_PROVISIONED")).hasSize(1);

    // The provisioned Hierarchy signs in with email + password (no OTP) and is routed by role.
    MvcResult login =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                        Map.of("email", "hank@ihrms", "password", "Overseer@2026"))))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(json.readTree(login.getResponse().getContentAsString()).get("session").get("role").asText())
        .isEqualTo("HIERARCHY");
  }

  @Test
  void superAdminCanRemoveAndReplaceTheHierarchy() throws Exception {
    provision("Harper Vue", "hank", "Overseer@2026");
    assertThat(users.existsByRole(UserRole.HIERARCHY)).isTrue();

    MvcResult removed =
        mvc.perform(delete("/provisioning/hierarchy").header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(removed.getResponse().getContentAsString()).get("exists").asBoolean()).isFalse();
    assertThat(users.existsByRole(UserRole.HIERARCHY)).isFalse();
    assertThat(auditLogs.findByAction("HIERARCHY_REMOVED")).hasSize(1);

    // A brand-new Hierarchy (even reusing the freed mailbox name) can now be provisioned.
    provision("Dana Digits", "hank", "Overseer@2027");
    assertThat(users.existsByRole(UserRole.HIERARCHY)).isTrue();

    // Only SUPER_ADMIN may provision/remove.
    String hrToken = token(user(companyId, UserRole.HR, "hr9@a.test"), UserRole.HR);
    mvc.perform(delete("/provisioning/hierarchy").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/provisioning/hierarchy")
                .header("Authorization", "Bearer " + hrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", "Nope", "localPart", "nope", "password", "Nope@2026"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void hierarchyIsReadOnlyAggregatesOnlyAndCannotReachPiiOrWrites() throws Exception {
    // A HIERARCHY principal (companyId = null), token issued directly.
    String hier =
        token(user(null, UserRole.HIERARCHY, "hank@ihrms"), UserRole.HIERARCHY);

    // Writes are refused (403): company creation (SUPER_ADMIN-only), employee onboarding (HR-only).
    forbidden(hier, post("/companies").content("{}"));
    forbidden(hier, post("/employees").content("{}"));
    // Individual record / PII is refused (the record read is HR/COMPANY_ADMIN/SUPER_ADMIN only).
    forbidden(hier, get("/employees/whatever/record"));
    // The read-only viewer area (Accounts Admin / Accountant) is NOT for Hierarchy.
    forbidden(hier, get("/accountant/companies"));
    // Attendance + leave team views (MANAGER-only) are refused.
    forbidden(hier, get("/attendance/team/whatever"));
    forbidden(hier, get("/leave/team/whatever"));
    // It cannot self-inspect provisioning (SUPER_ADMIN-only).
    forbidden(hier, get("/provisioning/hierarchy"));

    // The /hierarchy/** namespace is role-gated: a wrong role gets 403; HIERARCHY reaches its own
    // aggregate reads (added in the analytics stage) — a 200, not a 403. A missing /hierarchy path 404s.
    String hrToken = token(user(companyId, UserRole.HR, "hr@a.test"), UserRole.HR);
    mvc.perform(get("/hierarchy/overview").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/hierarchy/overview").header("Authorization", "Bearer " + hier))
        .andExpect(status().isOk());
    mvc.perform(get("/hierarchy/no-such-path").header("Authorization", "Bearer " + hier))
        .andExpect(status().isNotFound());
  }

  // --- helpers --------------------------------------------------------------

  private void forbidden(String token, MockHttpServletRequestBuilder req) throws Exception {
    mvc.perform(req.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  private JsonNode provisioningStatus(String token) throws Exception {
    return json.readTree(
        mvc.perform(get("/provisioning/hierarchy").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void provision(String name, String localPart, String password) throws Exception {
    mvc.perform(
            post("/provisioning/hierarchy")
                .header("Authorization", "Bearer " + superToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", name, "localPart", localPart, "password", password))))
        .andExpect(status().isCreated());
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private String token(User u, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), role, u.getCompanyId(), null));
  }
}
