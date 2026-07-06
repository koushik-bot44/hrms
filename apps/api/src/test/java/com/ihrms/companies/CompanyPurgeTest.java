package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Signature;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.SignatureRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Permanent purge (§7): {@code DELETE /companies/{id}/purge} hard-deletes a company and ALL of its
 * data across every table, leaves other companies untouched, and keeps a portal-level
 * {@code COMPANY_PURGED} trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class CompanyPurgeTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired Form1PersonalRepository form1s;
  @Autowired DocumentRepository documents;
  @Autowired SignatureRepository signatures;
  @Autowired GeneratedDocumentRepository generated;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String superToken;
  private String companyA;
  private String companyB;
  private String empA;
  private String mgrA;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"document_blobs\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");

    User superAdmin = staff("Super Admin", "super@root.test", UserRole.SUPER_ADMIN, null);
    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                superAdmin.getId(), superAdmin.getEmail(), superAdmin.getName(),
                UserRole.SUPER_ADMIN, null, null));

    companyA = seedFullCompany("Acme Inc", "ACME", "a");
    companyB = seedFullCompany("Beta LLC", "BETA", "b"); // control — must survive untouched
  }

  @Test
  void purgeHardDeletesEverythingForTheCompanyOnly() throws Exception {
    // Sanity: both companies are fully populated before the purge.
    assertThat(count("companies", "id", companyA)).isEqualTo(1);
    assertThat(count("employees", "companyId", companyA)).isEqualTo(1);
    assertThat(count("audit_logs", "companyId", companyA)).isEqualTo(1);

    mvc.perform(delete("/companies/" + companyA + "/purge").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isOk());

    // Company A: gone from every table.
    assertThat(count("companies", "id", companyA)).isZero();
    assertThat(count("users", "companyId", companyA)).isZero();
    assertThat(count("employees", "companyId", companyA)).isZero();
    assertThat(count("teams", "companyId", companyA)).isZero();
    assertThat(countByEmployee("form1_personal", empA)).isZero();
    assertThat(countByEmployee("documents", empA)).isZero();
    assertThat(countByEmployee("signatures", empA)).isZero();
    assertThat(countByEmployee("generated_documents", empA)).isZero();
    assertThat(countByEmployee("approval_requests", empA)).isZero();
    assertThat(count("notifications", "recipientUserId", mgrA)).isZero();
    assertThat(count("employee_code_sequences", "companyId", companyA)).isZero();
    assertThat(count("audit_logs", "companyId", companyA)).isZero();
    assertThat(blobCount("a/")).isZero();

    // Company B: entirely untouched.
    assertThat(count("companies", "id", companyB)).isEqualTo(1);
    assertThat(count("users", "companyId", companyB)).isEqualTo(2);
    assertThat(count("employees", "companyId", companyB)).isEqualTo(1);
    assertThat(count("teams", "companyId", companyB)).isEqualTo(1);
    assertThat(count("audit_logs", "companyId", companyB)).isEqualTo(1);
    assertThat(count("employee_code_sequences", "companyId", companyB)).isEqualTo(1);
    assertThat(blobCount("b/")).isEqualTo(3);

    // A portal-level COMPANY_PURGED trace survives (no companyId, records what was removed).
    assertThat(auditLogs.findByAction("COMPANY_PURGED"))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getCompanyId()).isNull();
              assertThat(row.getTargetId()).isEqualTo(companyA);
            });
  }

  // --- seeding --------------------------------------------------------------

  /** Company + HR + Manager + team + one employee with a full record + blobs + sequence + audit. */
  private String seedFullCompany(String name, String code, String slug) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    companies.save(company);
    String cid = company.getId();

    User hr = staff("HR " + code, "hr-" + slug + "@x.test", UserRole.HR, cid);
    User mgr = staff("Mgr " + code, "mgr-" + slug + "@x.test", UserRole.MANAGER, cid);

    Team team = new Team();
    team.setCompanyId(cid);
    team.setName("Team " + code);
    team.setHrUserId(hr.getId());
    team.setManagerUserId(mgr.getId());
    teams.save(team);

    Employee e = new Employee();
    e.setFullName("Emp " + code);
    e.setEmail("emp-" + slug + "@personal.test");
    e.setCompanyId(cid);
    e.setOnboardingHrId(hr.getId());
    e.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(e);

    Form1Personal f1 = new Form1Personal();
    f1.setEmployeeId(e.getId());
    f1.setStatus(SectionStatus.SUBMITTED);
    form1s.save(f1);

    document(e.getId(), slug + "/doc");
    signature(e.getId(), slug + "/sig");
    generatedDoc(e.getId(), slug + "/gen");

    ApprovalRequest appr = new ApprovalRequest();
    appr.setEmployeeId(e.getId());
    appr.setHrUserId(hr.getId());
    appr.setManagerUserId(mgr.getId());
    appr.setTeamId(team.getId());
    appr.setStatus(ApprovalStatus.PENDING);
    approvals.save(appr);

    Notification n = new Notification();
    n.setRecipientUserId(mgr.getId());
    n.setType(NotificationType.APPROVAL_REQUESTED);
    n.setEmployeeId(e.getId());
    notifications.save(n);

    blob(slug + "/doc");
    blob(slug + "/sig");
    blob(slug + "/gen");
    jdbc.update(
        "INSERT INTO \"employee_code_sequences\" (\"id\",\"companyId\",\"lastSeq\",\"updatedAt\") VALUES (?,?,?, now())",
        "seq-" + slug, cid, 1);
    jdbc.update(
        "INSERT INTO \"audit_logs\" (\"id\",\"companyId\",\"actorType\",\"action\") VALUES (?,?,?,?)",
        "aud-" + slug, cid, "USER", "SEEDED");

    if ("a".equals(slug)) {
      empA = e.getId();
      mgrA = mgr.getId();
    }
    return cid;
  }

  private void document(String employeeId, String storageKey) {
    Document d = new Document();
    d.setEmployeeId(employeeId);
    d.setDocType(DocumentType.PAN);
    d.setFileName("pan.pdf");
    d.setStorageKey(storageKey);
    d.setMimeType("application/pdf");
    d.setStatus(DocumentStatus.UPLOADED);
    documents.save(d);
  }

  private void signature(String employeeId, String storageKey) {
    Signature s = new Signature();
    s.setEmployeeId(employeeId);
    s.setStorageKey(storageKey);
    signatures.save(s);
  }

  private void generatedDoc(String employeeId, String storageKey) {
    GeneratedDocument g = new GeneratedDocument();
    g.setEmployeeId(employeeId);
    g.setKind(GeneratedDocumentKind.FORM1);
    g.setFileName("form1.pdf");
    g.setStorageKey(storageKey);
    generated.save(g);
  }

  private void blob(String storageKey) {
    jdbc.update(
        "INSERT INTO \"document_blobs\" (\"storageKey\",\"data\",\"sizeBytes\") VALUES (?,?,?)",
        storageKey, new byte[] {1, 2, 3}, 3);
  }

  private User staff(String name, String email, UserRole role, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  // --- counts ---------------------------------------------------------------

  private long count(String table, String column, String value) {
    Long c =
        jdbc.queryForObject(
            "SELECT count(*) FROM \"" + table + "\" WHERE \"" + column + "\" = ?", Long.class, value);
    return c == null ? 0 : c;
  }

  private long countByEmployee(String table, String employeeId) {
    return count(table, "employeeId", employeeId);
  }

  private long blobCount(String prefix) {
    Long c =
        jdbc.queryForObject(
            "SELECT count(*) FROM \"document_blobs\" WHERE \"storageKey\" LIKE ?", Long.class, prefix + "%");
    return c == null ? 0 : c;
  }
}
