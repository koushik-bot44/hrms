package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Permanent purge removes the REAL storage objects, not just the DB rows. Runs against local MinIO
 * (gated on {@code IHRMS_TEST_S3} + {@code STORAGE_DRIVER=s3} so {@code ./mvnw package} stays green
 * without S3): two companies each own an uploaded document + a generated PDF in the bucket; purging
 * one deletes its objects (post-commit) while the other company's objects remain.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_S3", matches = ".+")
class CompanyPurgeS3Test {

  private static final byte[] BYTES = "%PDF-1.4 purge-s3 test".getBytes(StandardCharsets.UTF_8);

  @Autowired MockMvc mvc;
  @Autowired TokenService tokens;
  @Autowired StorageService storage;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired DocumentRepository documents;
  @Autowired GeneratedDocumentRepository generated;
  @Autowired JdbcTemplate jdbc;

  private String superToken;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    User su = staff("Super Admin", "super@root.test", UserRole.SUPER_ADMIN, null);
    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(su.getId(), su.getEmail(), su.getName(), UserRole.SUPER_ADMIN, null, null));
  }

  @Test
  void purgeDeletesTheCompanysS3ObjectsButNotAnotherCompanys() throws Exception {
    String[] a = seedCompanyWithObjects("Acme Inc", "ACME");
    String[] b = seedCompanyWithObjects("Beta LLC", "BETA");
    String companyA = a[0], docA = a[1], genA = a[2];
    String docB = b[1], genB = b[2];

    // Both companies' objects are really in the bucket to start.
    assertThat(storage.getObjectBytes(docA)).isEqualTo(BYTES);
    assertThat(storage.getObjectBytes(genA)).isEqualTo(BYTES);
    assertThat(storage.getObjectBytes(docB)).isEqualTo(BYTES);

    mvc.perform(delete("/companies/" + companyA + "/purge").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isOk());

    // Company A's objects are gone from the bucket (post-commit cleanup ran)...
    assertThatThrownBy(() -> storage.getObjectBytes(docA)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> storage.getObjectBytes(genA)).isInstanceOf(RuntimeException.class);

    // ...while company B's objects are untouched.
    assertThat(storage.getObjectBytes(docB)).isEqualTo(BYTES);
    assertThat(storage.getObjectBytes(genB)).isEqualTo(BYTES);

    // Cleanup B's objects so repeated runs don't accumulate.
    storage.delete(docB);
    storage.delete(genB);
  }

  /** Returns {companyId, documentKey, generatedKey}, with the real objects PUT into the bucket. */
  private String[] seedCompanyWithObjects(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    companies.save(company);

    User hr = staff("HR " + code, "hr-" + code.toLowerCase() + "@x.test", UserRole.HR, company.getId());
    Employee e = new Employee();
    e.setFullName("Emp " + code);
    e.setEmail("emp-" + code.toLowerCase() + "@personal.test");
    e.setCompanyId(company.getId());
    e.setOnboardingHrId(hr.getId());
    e.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(e);

    String docKey = storage.buildKey(company.getId(), e.getId(), "form4", "pan.pdf");
    String genKey = storage.buildKey(company.getId(), e.getId(), "generated", "form1.pdf");
    storage.putObject(docKey, BYTES, "application/pdf");
    storage.putObject(genKey, BYTES, "application/pdf");

    Document d = new Document();
    d.setEmployeeId(e.getId());
    d.setDocType(DocumentType.PAN);
    d.setFileName("pan.pdf");
    d.setStorageKey(docKey);
    d.setMimeType("application/pdf");
    d.setStatus(DocumentStatus.UPLOADED);
    documents.save(d);

    GeneratedDocument g = new GeneratedDocument();
    g.setEmployeeId(e.getId());
    g.setKind(GeneratedDocumentKind.FORM1);
    g.setFileName("form1.pdf");
    g.setStorageKey(genKey);
    generated.save(g);

    return new String[] {company.getId(), docKey, genKey};
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
}
