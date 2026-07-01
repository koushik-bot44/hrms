package com.ihrms.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Reproduces the LIVE manager-approval path with REAL DB-backed storage (STORAGE_DRIVER=db) — the
 * path {@link ManagerApiTest} skips because it mocks the storage service. Approving an employee with
 * no completed forms/signature still generates + stores the five PDFs (into {@code document_blobs})
 * and must succeed.
 */
@SpringBootTest
@TestPropertySource(properties = "app.storage.driver=db")
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ManagerApproveDbStorageTest {

  @Autowired ManagerService managerService;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired GeneratedDocumentRepository generated;
  @Autowired JdbcTemplate jdbc;

  private String companyId;
  private User manager;
  private ApprovalRequest approval;
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

    User hr = user(UserRole.HR, "hr@testco.app");
    manager = user(UserRole.MANAGER, "manager@testco.app");
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hr.getId());
    t.setManagerUserId(manager.getId());
    teams.save(t);

    employee = new Employee();
    employee.setFullName("Spam Person");
    employee.setEmail("spam@gmail.com");
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(hr.getId());
    employee.setStatus(EmployeeStatus.HR_VERIFIED);
    employees.save(employee);

    approval = new ApprovalRequest();
    approval.setEmployeeId(employee.getId());
    approval.setHrUserId(hr.getId());
    approval.setManagerUserId(manager.getId());
    approval.setTeamId(t.getId());
    approval.setStatus(ApprovalStatus.PENDING);
    approvals.save(approval);
  }

  @Test
  void approveWithRealDbStorageMintsIdAndStoresThePdfs() {
    IhrmsPrincipal.User principal =
        new IhrmsPrincipal.User(
            manager.getId(), manager.getEmail(), manager.getName(), UserRole.MANAGER, companyId, null);

    // Mirror the controller: approve commits, then PDFs regenerate post-commit (best-effort).
    var result = managerService.approve(principal, approval.getId());
    managerService.regeneratePdfsForApproval(principal, approval.getId());

    assertThat(result.status()).isEqualTo(ApprovalStatus.APPROVED);
    assertThat(result.employeeCode()).isEqualTo("TESTCO-EMP-000001");
    assertThat(employees.findById(employee.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.APPROVED);
    // Five PDFs generated (one per form + merged) and their bytes stored in document_blobs.
    assertThat(generated.findByEmployeeId(employee.getId())).hasSize(5);
    Integer blobs =
        jdbc.queryForObject("SELECT COUNT(*) FROM \"document_blobs\"", Integer.class);
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
