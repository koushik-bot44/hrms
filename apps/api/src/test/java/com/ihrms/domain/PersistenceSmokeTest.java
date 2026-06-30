package com.ihrms.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.ProfileSection;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.ProfileSectionRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.EmployeeCodeService;
import com.ihrms.domain.support.EmployeeCodes;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence smoke test against a real local Postgres (gated on {@code IHRMS_TEST_DB}).
 * Verifies: every aggregate persists + reads; the atomic per-company code sequence; and
 * that AuditLog rows cannot be updated or deleted.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class PersistenceSmokeTest {

  @Autowired UserRepository users;
  @Autowired CompanyRepository companies;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired ProfileSectionRepository sections;
  @Autowired DocumentRepository documents;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired EmployeeCodeService employeeCodes;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
  }

  @Test
  void persistsAndReadsEveryAggregate() {
    Company company = newCompany("ACME");
    companies.save(company);
    assertThat(company.getId()).startsWith("c"); // cuid generated
    assertThat(company.getCreatedAt()).isNotNull();

    User hr = newUser("hr@acme.test", UserRole.HR, company.getId());
    User manager = newUser("mgr@acme.test", UserRole.MANAGER, company.getId());
    users.save(hr);
    users.save(manager);

    Team team = new Team();
    team.setCompanyId(company.getId());
    team.setName("Engineering");
    team.setHrUserId(hr.getId());
    team.setManagerUserId(manager.getId());
    teams.save(team);

    Employee employee = new Employee();
    employee.setEmployeeCode("ACME-EMP-000001");
    employee.setEmail("alex@personal.test");
    employee.setCompanyId(company.getId());
    employee.setOnboardingHrId(hr.getId());
    employees.save(employee);

    ProfileSection section = new ProfileSection();
    section.setEmployeeId(employee.getId());
    section.setKey(SectionKey.PERSONAL);
    section.setData(Map.of("fullName", "Alex Doe", "city", "Metro"));
    sections.save(section);

    Document document = new Document();
    document.setEmployeeId(employee.getId());
    document.setSectionKey(SectionKey.GOVERNMENT);
    document.setDocType(DocumentType.PAN);
    document.setFileName("pan.pdf");
    document.setStorageKey("companies/x/employees/y/pan.pdf");
    document.setMimeType("application/pdf");
    documents.save(document);

    ApprovalRequest approval = new ApprovalRequest();
    approval.setEmployeeId(employee.getId());
    approval.setHrUserId(hr.getId());
    approval.setManagerUserId(manager.getId());
    approval.setTeamId(team.getId());
    approvals.save(approval);

    Notification notification = new Notification();
    notification.setRecipientUserId(manager.getId());
    notification.setType(NotificationType.EMPLOYEE_SUBMITTED);
    notification.setEmployeeId(employee.getId());
    notifications.save(notification);

    AuditLog audit = new AuditLog();
    audit.setActorType("USER");
    audit.setActorId(hr.getId());
    audit.setCompanyId(company.getId());
    audit.setAction("EMPLOYEE_ONBOARDED");
    audit.setMetadata(Map.of("employeeCode", "ACME-EMP-000001"));
    auditLogs.save(audit);

    // Read back: enum + JSON + timestamp round-trips, and the relations resolve by id.
    assertThat(companies.findByCode("ACME")).get().extracting(Company::getId).isEqualTo(company.getId());
    assertThat(users.findByEmail("hr@acme.test")).get().extracting(User::getRole).isEqualTo(UserRole.HR);
    assertThat(employees.findByEmployeeCode("ACME-EMP-000001")).isPresent();
    assertThat(teams.findById(team.getId())).get().extracting(Team::getHrUserId).isEqualTo(hr.getId());
    ProfileSection readSection = sections.findByEmployeeIdAndKey(employee.getId(), SectionKey.PERSONAL).orElseThrow();
    assertThat(readSection.getData()).containsEntry("fullName", "Alex Doe");
    assertThat(documents.findByEmployeeId(employee.getId())).singleElement().extracting(Document::getStatus).isEqualTo(DocumentStatus.UPLOADED);
    assertThat(approvals.findByManagerUserId(manager.getId())).hasSize(1);
    assertThat(notifications.findByRecipientUserId(manager.getId())).hasSize(1);
    AuditLog readAudit = auditLogs.findById(audit.getId()).orElseThrow();
    assertThat(readAudit.getMetadata()).containsEntry("employeeCode", "ACME-EMP-000001");
    assertThat(readAudit.getCreatedAt()).isNotNull();
  }

  @Test
  void allocatesCompanyCodesAtomically() {
    Company company = newCompany("ZZZ");
    companies.save(company);

    assertThat(employeeCodes.allocateSequence(company.getId())).isEqualTo(1);
    assertThat(employeeCodes.allocateSequence(company.getId())).isEqualTo(2);

    int n = 16;
    Set<Integer> parallel =
        IntStream.range(0, n)
            .parallel()
            .mapToObj(i -> employeeCodes.allocateSequence(company.getId()))
            .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
    assertThat(parallel).hasSize(n); // no collisions under concurrency

    assertThat(EmployeeCodes.format(company.getCode(), 1)).isEqualTo("ZZZ-EMP-000001");
    assertThat(employeeCodes.nextCode(company.getId(), company.getCode())).matches(EmployeeCodes.EMPLOYEE_CODE);
  }

  @Test
  void auditLogIsAppendOnly() {
    AuditLog audit = new AuditLog();
    audit.setActorType("SYSTEM");
    audit.setAction("APPEND_ONLY_PROBE");
    auditLogs.save(audit);
    String id = audit.getId();

    assertThatThrownBy(() -> jdbc.update("UPDATE \"audit_logs\" SET \"action\"='hacked' WHERE \"id\"=?", id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM \"audit_logs\" WHERE \"id\"=?", id))
        .isInstanceOf(DataAccessException.class);

    assertThat(auditLogs.findById(id)).get().extracting(AuditLog::getAction).isEqualTo("APPEND_ONLY_PROBE");
  }

  private Company newCompany(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return c;
  }

  private User newUser(String email, UserRole role, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return u;
  }
}
