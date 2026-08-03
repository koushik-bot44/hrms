package com.ihrms.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.review.dto.ReviewDtos.ApproveRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Reproduces the LIVE HR-approval path with REAL DB-backed storage (STORAGE_DRIVER=db) — the path
 * {@link ReviewApiTest} skips because it mocks the storage service. Approving a verified employee with no
 * completed forms/signature still mints the ID and generates + stores the five PDFs (into {@code
 * document_blobs}), and must succeed.
 */
@SpringBootTest
@TestPropertySource(properties = "app.storage.driver=db")
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class HrApproveDbStorageTest {

  @Autowired ReviewService reviewService;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired GeneratedDocumentRepository generated;
  @Autowired JdbcTemplate jdbc;

  private String companyId;
  private User hr;
  private Employee employee;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"document_blobs\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    Company c = new Company();
    c.setName("Test Co");
    c.setCode("TESTCO");
    companyId = companies.save(c).getId();

    hr = user(UserRole.HR, "hr@testco.app");
    User manager = user(UserRole.MANAGER, "manager@testco.app");
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hr.getId()); // approve resolves the employee's team via this HR
    t.setManagerUserId(manager.getId());
    teams.save(t);

    employee = new Employee();
    employee.setFullName("Spam Person");
    employee.setEmail("spam@gmail.com");
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(hr.getId());
    employee.setStatus(EmployeeStatus.HR_VERIFIED); // verified + awaiting the HR decision
    employees.save(employee);
  }

  @Test
  void hrApproveWithRealDbStorageMintsIdAndStoresThePdfs() {
    IhrmsPrincipal.User principal =
        new IhrmsPrincipal.User(hr.getId(), hr.getEmail(), hr.getName(), UserRole.HR, companyId, null);

    // Mirror the controller: approve commits, then PDFs regenerate post-commit (best-effort).
    var result = reviewService.approve(principal, employee.getId(), new ApproveRequest(null), "127.0.0.1");
    reviewService.regeneratePdfsQuietly(employee.getId());

    assertThat(result.status()).isEqualTo(EmployeeStatus.APPROVED);
    assertThat(result.employeeCode()).isEqualTo("TESTCO-EMP-000001");
    assertThat(employees.findById(employee.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.APPROVED);
    // Five PDFs generated (one per form + merged) and their bytes stored in document_blobs.
    assertThat(generated.findByEmployeeId(employee.getId())).hasSize(5);
    Integer blobs = jdbc.queryForObject("SELECT COUNT(*) FROM \"document_blobs\"", Integer.class);
    assertThat(blobs).isEqualTo(5);
  }

  private User user(UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }
}
