package com.ihrms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.auth.dto.AuthDtos.OtpRequest;
import com.ihrms.auth.dto.AuthDtos.OtpRequestResult;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/**
 * devOtp HARDENING (§Outbound email): with SMTP configured (real delivery), the OTP travels by email ONLY —
 * {@code requestOtp} must NOT return it. This sets SMTP_HOST so {@link com.ihrms.email.SmtpMailer} is selected
 * (JavaMailSender is @MockBean'd → zero network), and asserts the mail mode + a withheld devOtp. The DEV-log
 * side (devOtp surfaced when no SMTP) is asserted by {@code AuthFlowTest} on the default (dev) context.
 */
@SpringBootTest
@TestPropertySource(properties = {"app.mail.host=smtp.test.invalid", "spring.mail.host=smtp.test.invalid"})
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class DevOtpMailModeTest {

  @MockBean JavaMailSender mailSender; // real SMTP selected, but the sender is mocked → no network

  @Autowired AuthService auth;
  @Autowired MailService mail;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired CompanyRepository companies;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\","
            + "\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
  }

  @Test
  void smtpModeIsRealAndWithholdsDevOtp() {
    // A real, OTP-eligible employee (name-matched, active company, not deactivated).
    Company company = new Company();
    company.setName("Zeta Inc");
    company.setCode("ZETA");
    companies.save(company);

    User hr = new User();
    hr.setEmail("hr-zeta@acme.test");
    hr.setName("HR Zeta");
    hr.setRole(UserRole.HR);
    hr.setCompanyId(company.getId());
    hr.setPasswordHash(encoder.encode("Passw0rd!"));
    hr.setStatus("ACTIVE");
    users.save(hr);

    Employee employee = new Employee();
    employee.setFullName("Zoe Test");
    employee.setEmail("zoe@personal.test");
    employee.setCompanyId(company.getId());
    employee.setOnboardingHrId(hr.getId());
    employees.save(employee);

    assertThat(mail.isRealDelivery()).isTrue();

    OtpRequestResult result = auth.requestOtp(new OtpRequest("Zoe Test", "zoe@personal.test"));
    assertThat(result.sent()).isTrue();
    assertThat(result.devOtp()).isNull(); // withheld — the code went by email only
  }
}
