package com.ihrms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.support.FieldCrypto;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one-time field-encryption re-key migration (§6): values written under the old dev key (the situation the
 * prod boot guard caught — FIELD_ENC_KEY unset) are re-encrypted onto a new key without data loss. Gated on a
 * local Postgres. The active FieldCrypto key is switched during the test and RESTORED afterward so other
 * @SpringBootTest classes in the shared context are unaffected.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class FieldKeyReencryptorTest {

  private static final String NEW_KEY = "a-brand-new-production-field-enc-key-9f8e7d6c";

  @Autowired JdbcTemplate jdbc;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired Form1PersonalRepository form1s;
  @Autowired Form2InfoRepository form2s;
  @Autowired Form3PrevEmploymentRepository form3s;

  private String empId;
  private String f1Id;
  private String f2Id;
  private String f3Id;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"form1_personal\",\"form2_info\",\"form3_prev_employment\" RESTART IDENTITY CASCADE");
    // Setup must encrypt with the OLD (dev) key — the prod situation when FIELD_ENC_KEY was unset.
    FieldCrypto.configure(FieldCrypto.DEV_DEFAULT_KEY);

    Company c = new Company();
    c.setName("Acme");
    c.setCode("ACME");
    String companyId = companies.save(c).getId();

    User hr = new User();
    hr.setEmail("hr@acme.test");
    hr.setName("HR");
    hr.setRole(UserRole.HR);
    hr.setCompanyId(companyId);
    String hrId = users.save(hr).getId();

    Employee e = new Employee();
    e.setFullName("Evan Stone");
    e.setEmail("evan@personal.test");
    e.setDesignation("Software Engineer");
    e.setDateOfJoining(LocalDate.parse("2026-07-01"));
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.SUBMITTED);
    e.setAadhaarNumber("123412341234");
    empId = employees.save(e).getId();

    Form1Personal f1 = new Form1Personal();
    f1.setEmployeeId(empId);
    f1.setData(Map.of("name", "Evan"));
    f1.setOfferedCtc("1500000");
    f1Id = form1s.save(f1).getId();

    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(empId);
    f2.setData(Map.of("fullName", "Evan"));
    f2.setPanNumber("ABCDE1234F");
    f2.setAxisAccountNumber("9988776655");
    f2Id = form2s.save(f2).getId();

    Form3PrevEmployment f3 = new Form3PrevEmployment();
    f3.setEmployeeId(empId);
    f3.setOrderIndex(0);
    f3.setLastDrawnSalary("1200000");
    f3Id = form3s.save(f3).getId();
  }

  @AfterEach
  void restore() {
    FieldCrypto.configure(FieldCrypto.DEV_DEFAULT_KEY);
  }

  @Test
  void reencryptsExistingValuesFromTheOldDevKeyToTheNewKey() {
    // Rotate the active key to a NEW one (as the operator does by setting FIELD_ENC_KEY).
    FieldCrypto.configure(NEW_KEY);

    // The stored values are still under the OLD dev key, so loading them under the NEW key FAILS.
    assertThatThrownBy(() -> employees.findById(empId).orElseThrow().getAadhaarNumber());

    // Run the one-time re-key migration: previous = the old dev key, target = current (NEW) key.
    new FieldKeyReencryptor(jdbc, true, FieldCrypto.DEV_DEFAULT_KEY).run(null);

    // Every sensitive field now reads correctly under the NEW key — no data lost.
    assertThat(employees.findById(empId).orElseThrow().getAadhaarNumber()).isEqualTo("123412341234");
    assertThat(form1s.findById(f1Id).orElseThrow().getOfferedCtc()).isEqualTo("1500000");
    Form2Info f2 = form2s.findById(f2Id).orElseThrow();
    assertThat(f2.getPanNumber()).isEqualTo("ABCDE1234F");
    assertThat(f2.getAxisAccountNumber()).isEqualTo("9988776655");
    assertThat(form3s.findById(f3Id).orElseThrow().getLastDrawnSalary()).isEqualTo("1200000");

    // The raw stored ciphertext is no longer decryptable by the old dev key.
    String rawPan =
        jdbc.queryForObject("SELECT \"panNumber\" FROM \"form2_info\" WHERE \"id\"=?", String.class, f2Id);
    assertThat(FieldCrypto.isEncrypted(rawPan)).isTrue();
    assertThatThrownBy(() -> FieldCrypto.decryptWithKey(rawPan, FieldCrypto.DEV_DEFAULT_KEY));

    // Idempotent: a second run leaves values intact + readable (nothing double-wrapped or corrupted).
    new FieldKeyReencryptor(jdbc, true, FieldCrypto.DEV_DEFAULT_KEY).run(null);
    assertThat(form2s.findById(f2Id).orElseThrow().getPanNumber()).isEqualTo("ABCDE1234F");
  }

  @Test
  void disabledRunnerIsANoOp() {
    FieldCrypto.configure(NEW_KEY);
    new FieldKeyReencryptor(jdbc, false, FieldCrypto.DEV_DEFAULT_KEY).run(null);
    // No migration happened, so the old-key values stay unreadable under the NEW key.
    assertThatThrownBy(() -> form2s.findById(f2Id).orElseThrow().getPanNumber());
  }
}
