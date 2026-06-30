package com.ihrms.employees;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ihrms.domain.support.EmployeeCodes;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HR onboarding (contract §3.5): minted code, INVITED + login link, HR scoping, audit, concurrency. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class EmployeesApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired EmployeesService employeesService;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String companyId;
  private User hr;
  private String hrToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyId = company("ACME");
    hr = hrUser(companyId, "hr1@acme.test");
    hrToken = tokenFor(hr);
  }

  @Test
  void onboardMintsSequentialCodesEmailsLinkAndAudits(CapturedOutput output) throws Exception {
    MvcResult first =
        mvc.perform(asHr("Alex@Personal.TEST"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = json.readTree(first.getResponse().getContentAsString());
    JsonNode employee = body.get("employee");
    assertThat(employee.get("employeeCode").asText()).isEqualTo("ACME-EMP-000001");
    assertThat(employee.get("email").asText()).isEqualTo("alex@personal.test"); // lower-cased
    assertThat(employee.get("status").asText()).isEqualTo("INVITED");
    assertThat(employee.get("createdAt").asText()).isNotBlank();
    assertThat(body.get("loginUrl").asText()).endsWith("/login");
    // The onboarding email fires — the dev log carries the minted ID + login link.
    assertThat(output.getOut()).contains("[DEV ONBOARDING]").contains("ACME-EMP-000001");

    // Second onboard increments the per-company sequence.
    MvcResult second = mvc.perform(asHr("sam@personal.test")).andExpect(status().isCreated()).andReturn();
    assertThat(json.readTree(second.getResponse().getContentAsString())
            .get("employee").get("employeeCode").asText())
        .isEqualTo("ACME-EMP-000002");

    // GET lists both, newest first.
    MvcResult list =
        mvc.perform(get("/employees").header("Authorization", "Bearer " + hrToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode arr = json.readTree(list.getResponse().getContentAsString());
    assertThat(arr).hasSize(2);
    assertThat(arr.get(0).get("employeeCode").asText()).isEqualTo("ACME-EMP-000002");

    // Audit row, scoped to the HR's company, with the acting HR as actor.
    assertThat(auditLogs.findByAction("EMPLOYEE_ONBOARDED"))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row.getCompanyId()).isEqualTo(companyId);
              assertThat(row.getActorType()).isEqualTo("USER");
              assertThat(row.getActorId()).isEqualTo(hr.getId());
            });
  }

  @Test
  void hrSeesOnlyTheEmployeesItOnboarded() throws Exception {
    mvc.perform(asHr("e1@personal.test")).andExpect(status().isCreated());

    User otherHr = hrUser(companyId, "hr2@acme.test");
    String otherToken = tokenFor(otherHr);

    MvcResult mineList =
        mvc.perform(get("/employees").header("Authorization", "Bearer " + hrToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(mineList.getResponse().getContentAsString())).hasSize(1);

    MvcResult othersList =
        mvc.perform(get("/employees").header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(othersList.getResponse().getContentAsString())).isEmpty();
  }

  @Test
  void nonHrIsForbidden() throws Exception {
    String adminToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                "ca-1", "ca@acme.test", "CA", UserRole.COMPANY_ADMIN, companyId, null));
    mvc.perform(get("/employees").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void concurrentOnboardsAllocateDistinctWellFormedCodes() {
    IhrmsPrincipal.User actor =
        new IhrmsPrincipal.User(hr.getId(), hr.getEmail(), hr.getName(), UserRole.HR, companyId, null);
    int n = 12;
    Set<String> codes =
        IntStream.range(0, n)
            .parallel()
            .mapToObj(
                i ->
                    employeesService
                        .onboard(new OnboardEmployeeRequest("e" + i + "@personal.test"), actor, "127.0.0.1")
                        .employee()
                        .employeeCode())
            .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));

    assertThat(codes).hasSize(n); // no collisions under concurrency
    assertThat(codes).allMatch(code -> EmployeeCodes.EMPLOYEE_CODE.matcher(code).matches());
  }

  // --- fixtures -------------------------------------------------------------

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asHr(String email)
      throws Exception {
    return post("/employees")
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("email", email)));
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User hrUser(String companyId, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(UserRole.HR);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private String tokenFor(User user) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(
            user.getId(), user.getEmail(), user.getName(), UserRole.HR, user.getCompanyId(), null));
  }
}
