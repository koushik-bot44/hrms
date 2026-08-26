package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-building break thresholds: save, validate, audit.
 *
 * <p>The validation half matters more than it looks. Hibernate's {@code ddl-auto: validate} checks
 * tables, columns and types — never CHECK constraints — so a database constraint on its own reaches the
 * operator as a 500 they cannot act on. Standing rule D says every CHECK gets a bean-validation mirror,
 * and these tests are what stop that mirror silently rotting away from the constraint it mirrors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockSitePolicyApiTest {

  @Autowired MockMvc mvc;
  @Autowired TokenService tokens;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockSitePolicyService policies;
  @Autowired JdbcTemplate jdbc;

  private String siteId;
  private String superToken;
  private String hrToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"iclock_site_policies\",\"iclock_punch_members\",\"iclock_punches\","
            + "\"iclock_raw_punches\",\"iclock_people\",\"iclock_employee_pins\",\"iclock_devices\","
            + "\"iclock_site_companies\",\"iclock_sites\",\"audit_logs\" RESTART IDENTITY CASCADE");
    IclockSite site = new IclockSite();
    site.setName("Policy Test Building");
    siteId = sites.save(site).getId();

    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("s-1", "s@x.test", "Super", UserRole.SUPER_ADMIN, null, null));
    hrToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("hr-1", "hr@x.test", "HR", UserRole.HR, "c-1", null));
  }

  @Test
  void aBuildingWithNoStoredRowResolvesToDefaultsRatherThanFailing() throws Exception {
    // Sites created straight through the repository — as every DB-gated test does — have no policy row.
    // Resolution must be total without one, or the board 500s for any building made before V46.
    mvc.perform(get("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.breakAlertMin").value(30))
        .andExpect(jsonPath("$.breakAlertMaxMin").value(120))
        .andExpect(jsonPath("$.stored").value(false));
  }

  @Test
  void savingThresholdsPersistsThemAndTakesEffectImmediately() throws Exception {
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":20,\"breakAlertMaxMin\":90}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.breakAlertMin").value(20))
        .andExpect(jsonPath("$.stored").value(true));

    // No redeploy, no restart: the very next resolution sees it, which is what the board does on refresh.
    assertThat(policies.effective(siteId).breakAlertMin()).isEqualTo(20);
    assertThat(policies.effective(siteId).breakAlertMaxMin()).isEqualTo(90);
  }

  @Test
  void savingTwiceUpdatesTheSameRowRatherThanAccumulating() throws Exception {
    save(25, 100);
    save(35, 110);
    Long rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM \"iclock_site_policies\" WHERE \"siteId\" = ?", Long.class, siteId);
    assertThat(rows).isEqualTo(1);
    assertThat(policies.effective(siteId).breakAlertMin()).isEqualTo(35);
  }

  @Test
  void aThresholdBelowFiveMinutesIsA400WithAReadableMessage() throws Exception {
    // 400, never the 500 a bare CHECK violation produces.
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":2,\"breakAlertMaxMin\":90}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aCapAtOrBelowTheThresholdIsRefused() throws Exception {
    // Otherwise every alert retires the instant it fires — the feature would be silently inert.
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":60,\"breakAlertMaxMin\":60}"))
        .andExpect(status().isBadRequest());

    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":60,\"breakAlertMaxMin\":30}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aRejectedSaveLeavesTheStoredValueUntouched() throws Exception {
    save(25, 100);
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":99,\"breakAlertMaxMin\":10}"))
        .andExpect(status().isBadRequest());
    assertThat(policies.effective(siteId).breakAlertMin()).isEqualTo(25);
    assertThat(policies.effective(siteId).breakAlertMaxMin()).isEqualTo(100);
  }

  @Test
  void savingIsAudited() throws Exception {
    save(45, 150);
    // Queried through JDBC: AuditLogRepository also extends JpaSpecificationExecutor, so a bare
    // findAll() is ambiguous, and the metadata is jsonb which reads more honestly as text anyway.
    var rows =
        jdbc.queryForList(
            "SELECT \"targetId\", \"metadata\"::text AS meta FROM audit_logs WHERE action = ?",
            "ICLOCK_SITE_POLICY_UPDATED");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("targetId")).isEqualTo(siteId);
    assertThat((String) rows.get(0).get("meta")).contains("45").contains("150");
  }

  @Test
  void aFailedSaveWritesNoAuditEntry() throws Exception {
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":1,\"breakAlertMaxMin\":90}"))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ?",
                Long.class,
                "ICLOCK_SITE_POLICY_UPDATED"))
        .isZero();
  }

  @Test
  void anUnknownBuildingIs404() throws Exception {
    mvc.perform(put("/provisioning/iclock/sites/does-not-exist/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":20,\"breakAlertMaxMin\":90}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aNonSuperAdminCannotReadOrWriteThePolicy() throws Exception {
    // /provisioning/** is hasRole(SUPER_ADMIN) in the filter chain — the surface is fail-closed.
    mvc.perform(get("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + hrToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":20,\"breakAlertMaxMin\":90}"))
        .andExpect(status().isForbidden());
  }

  private void save(int min, int max) throws Exception {
    mvc.perform(put("/provisioning/iclock/sites/" + siteId + "/policy")
            .header("Authorization", "Bearer " + superToken)
            .contentType("application/json")
            .content("{\"breakAlertMin\":" + min + ",\"breakAlertMaxMin\":" + max + "}"))
        .andExpect(status().isOk());
  }
}
