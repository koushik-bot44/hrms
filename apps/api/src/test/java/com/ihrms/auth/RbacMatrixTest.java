package com.ihrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.domain.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The role-area RBAC matrix (contract §1.2): for each role, one allowed area and one
 * forbidden area, plus the unauthenticated case. Tokens are minted directly so the test
 * exercises the security filter chain, not the DB.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RbacProbeController.class)
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class RbacMatrixTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;

  @Test
  void superAdminMayManageCompaniesButNotTeams() throws Exception {
    allowed(userToken(UserRole.SUPER_ADMIN), "/companies/_probe");
    forbidden(userToken(UserRole.SUPER_ADMIN), "/teams/_probe");
  }

  @Test
  void companyAdminMayManageTeamsButNotCompanies() throws Exception {
    allowed(userToken(UserRole.COMPANY_ADMIN), "/teams/_probe");
    forbidden(userToken(UserRole.COMPANY_ADMIN), "/companies/_probe");
  }

  @Test
  void hrMayManageEmployeesButNotCompanies() throws Exception {
    allowed(userToken(UserRole.HR), "/employees/_probe");
    forbidden(userToken(UserRole.HR), "/companies/_probe");
  }

  @Test
  void managerIsAuthenticatedButNotASuperAdmin() throws Exception {
    allowed(userToken(UserRole.MANAGER), "/auth/me");
    forbidden(userToken(UserRole.MANAGER), "/companies/_probe");
  }

  @Test
  void employeeMayAccessOwnOnboardingButNotEmployeeAdmin() throws Exception {
    allowed(employeeToken(), "/me/onboarding/_probe");
    forbidden(employeeToken(), "/employees/_probe");
  }

  @Test
  void unauthenticatedRequestIs401WithEnvelope() throws Exception {
    MvcResult res =
        mvc.perform(get("/companies/_probe")).andExpect(status().isUnauthorized()).andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("statusCode").asInt()).isEqualTo(401);
    assertThat(body.get("error").asText()).isEqualTo("UNAUTHORIZED");
    assertThat(body.get("path").asText()).isEqualTo("/companies/_probe");
  }

  // --- helpers --------------------------------------------------------------

  private void allowed(String token, String path) throws Exception {
    mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
  }

  private void forbidden(String token, String path) throws Exception {
    MvcResult res =
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("statusCode").asInt()).isEqualTo(403);
    assertThat(body.get("error").asText()).isEqualTo("FORBIDDEN");
  }

  private String userToken(UserRole role) {
    String companyId = role == UserRole.SUPER_ADMIN ? null : "company-1";
    return tokens.issueAccess(
        new IhrmsPrincipal.User("user-" + role, role + "@x.test", "Name", role, companyId, null));
  }

  private String employeeToken() {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee("emp-1", "ACME-EMP-000001", "e@x.test", "company-1"));
  }
}
