package com.ihrms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Per-company audit explorer (§7): Super Admin reads any company's trail (one at a time, kept
 * separate); Company Admin is locked to their own company; cross-company + non-admin denied;
 * filter + pagination work; every query is companyId-scoped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuditApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired AuditLogRepository auditLogs;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;

  private String companyA;
  private String companyB;
  private String superToken;
  private String adminAToken;
  private String hrToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    companyB = company("BBB");
    User actorA = user(companyA, "actor-a@a.test");

    log(companyA, "USER", actorA.getId(), "COMPANY_CREATED");
    log(companyA, "USER", actorA.getId(), "TEAM_CREATED");
    log(companyA, "USER", actorA.getId(), "EMPLOYEE_ONBOARDED");
    log(companyB, "SYSTEM", null, "COMPANY_CREATED");
    log(companyB, "SYSTEM", null, "TEAM_CREATED");

    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("s-1", "s@x.test", "Super", UserRole.SUPER_ADMIN, null, null));
    adminAToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("ca-a", "ca@a.test", "CA", UserRole.COMPANY_ADMIN, companyA, null));
    hrToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("hr-a", "hr@a.test", "HR", UserRole.HR, companyA, null));
  }

  @Test
  void superAdminReadsEachCompanySeparatelyWithFilterAndPagination() throws Exception {
    JsonNode a = page(superToken, "companyId", companyA);
    assertThat(a.get("totalElements").asInt()).isEqualTo(3);
    a.get("content").forEach(row -> assertThat(row.get("companyId").asText()).isEqualTo(companyA));
    assertThat(a.get("content").get(0).get("actorLabel").asText()).isEqualTo("actor-a@a.test");

    // A different company's trail is a separate query.
    assertThat(page(superToken, "companyId", companyB).get("totalElements").asInt()).isEqualTo(2);

    // Super Admin must pick a company.
    mvc.perform(get("/audit").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isBadRequest());

    // action filter (case-insensitive contains).
    assertThat(page(superToken, "companyId", companyA, "action", "team").get("totalElements").asInt())
        .isEqualTo(1);

    // pagination.
    JsonNode paged = page(superToken, "companyId", companyA, "size", "2");
    assertThat(paged.get("content")).hasSize(2);
    assertThat(paged.get("totalElements").asInt()).isEqualTo(3);
    assertThat(paged.get("totalPages").asInt()).isEqualTo(2);

    // date range: nothing in the future.
    String future = Instant.now().plusSeconds(86_400).toString();
    assertThat(page(superToken, "companyId", companyA, "from", future).get("totalElements").asInt())
        .isZero();
  }

  /**
   * The PLATFORM slice — rows whose {@code companyId} is null because the action belonged to no
   * company (a SUPER_ADMIN login, a purge, a hierarchy change, an iClock device claim).
   *
   * <p>These were being written and then made unreadable: the list query was an unconditional
   * {@code companyId = ?}, and in SQL nothing equals NULL, so no parameter value could ever reach
   * them. The explorer looked empty for the one role entitled to see them.
   */
  @Test
  void superAdminCanReadThePlatformTrailAndItStaysSeparateFromCompanyTrails() throws Exception {
    log(null, "USER", "s-1", "COMPANY_PURGED");
    log(null, "USER", "s-1", "ICLOCK_DEVICE_CLAIMED");

    JsonNode platform = page(superToken, "companyId", "__platform__");
    assertThat(platform.get("totalElements").asInt()).isEqualTo(2);
    platform.get("content").forEach(row -> assertThat(row.get("companyId").isNull()).isTrue());

    // The separation still holds in BOTH directions: platform rows must not leak into a company's
    // trail, and a company's rows must not appear in the platform slice.
    assertThat(page(superToken, "companyId", companyA).get("totalElements").asInt()).isEqualTo(3);
    assertThat(page(superToken, "companyId", companyB).get("totalElements").asInt()).isEqualTo(2);

    // Filters still apply within the platform scope.
    assertThat(
            page(superToken, "companyId", "__platform__", "action", "iclock")
                .get("totalElements")
                .asInt())
        .isEqualTo(1);

    // A blank companyId is still a 400 — the sentinel is an explicit opt-in, not a default.
    mvc.perform(get("/audit").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void companyAdminCannotReachThePlatformTrailViaTheSentinel() throws Exception {
    // A COMPANY_ADMIN sending the sentinel is not treated as a scope request at all — they fall
    // through to being locked to their own company, exactly as if they had sent nothing.
    mvc.perform(
            get("/audit")
                .param("companyId", "__platform__")
                .header("Authorization", "Bearer " + adminAToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void companyAdminIsLockedToOwnCompanyAndCrossIsDenied() throws Exception {
    // No companyId param -> forced to their own company.
    assertThat(page(adminAToken).get("totalElements").asInt()).isEqualTo(3);
    assertThat(page(adminAToken).get("content").get(0).get("companyId").asText()).isEqualTo(companyA);

    // Asking for another company is forbidden.
    mvc.perform(
            get("/audit").param("companyId", companyB).header("Authorization", "Bearer " + adminAToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void nonAdminRolesCannotReadAudit() throws Exception {
    mvc.perform(get("/audit").param("companyId", companyA).header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode page(String token, String... params) throws Exception {
    var req = get("/audit").header("Authorization", "Bearer " + token);
    for (int i = 0; i + 1 < params.length; i += 2) {
      req = req.param(params[i], params[i + 1]);
    }
    MvcResult res = mvc.perform(req).andExpect(status().isOk()).andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User user(String companyId, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(UserRole.COMPANY_ADMIN);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private void log(String companyId, String actorType, String actorId, String action) {
    AuditLog l = new AuditLog();
    l.setCompanyId(companyId);
    l.setActorType(actorType);
    l.setActorId(actorId);
    l.setAction(action);
    l.setMetadata(Map.of("probe", true));
    auditLogs.save(l);
  }
}
