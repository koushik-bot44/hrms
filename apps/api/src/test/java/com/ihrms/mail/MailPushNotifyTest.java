package com.ihrms.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.MessageRecipientRepository;
import com.ihrms.domain.repository.MessageRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * § Web Push N3: a delivered internal message pushes an OS notification to every recipient (TO+CC+BCC)
 * EXCEPT the sender — title = sender, body = subject, url = /mail, and NO recipient information in the
 * payload (BCC privacy). Best-effort: a push failure never affects the mail. Push DELIVERY is disabled in
 * tests (no VAPID) — the spy records the trigger; real OS delivery is proven on the deployed HTTPS site.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class MailPushNotifyTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired MessageRepository messages;
  @Autowired MessageRecipientRepository recipients;
  @Autowired JdbcTemplate jdbc;
  @SpyBean PushService push;

  private String acme;
  private User admin; // COMPANY_ADMIN — may mail anyone in the company
  private User hr;
  private User manager;
  private Employee employee; // credentialed, onboarded by hr

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"messages\",\"message_recipients\",\"message_attachments\",\"push_subscriptions\","
            + "\"teams\",\"employees\",\"users\",\"companies\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");
    admin = user(acme, UserRole.COMPANY_ADMIN, "admin@acme");
    hr = user(acme, UserRole.HR, "hr@acme");
    manager = user(acme, UserRole.MANAGER, "mgr@acme");
    Team team = new Team();
    team.setName("Core");
    team.setCompanyId(acme);
    team.setHrUserId(hr.getId());
    team.setManagerUserId(manager.getId());
    teams.save(team);
    employee = employee(acme, hr, "arjun@acme");
  }

  @Test
  void composeNotifiesEveryRecipientExceptTheSender() throws Exception {
    compose(admin, List.of(hr.getId()), List.of(manager.getId()), List.of(employee.getId()), "Q3 numbers");

    String title = "New message from " + admin.getName();
    // One push per recipient — TO, CC and BCC alike — with a payload of ONLY sender/subject/url.
    verify(push).sendToPrincipal(PrincipalRef.forUser(hr.getId(), null), title, "Q3 numbers", "/mail");
    verify(push)
        .sendToPrincipal(PrincipalRef.forUser(manager.getId(), null), title, "Q3 numbers", "/mail");
    verify(push)
        .sendToPrincipal(
            PrincipalRef.forEmployee(employee.getId(), null), title, "Q3 numbers", "/mail");
    verify(push, times(3)).sendToPrincipal(any(), anyString(), anyString(), anyString());

    // The BCC recipient's push (same title/body as everyone's) names no other recipient — and no push
    // payload anywhere contains a recipient at all (title = sender, body = subject only).
    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(push, times(3)).sendToPrincipal(any(), anyString(), bodies.capture(), anyString());
    assertThat(bodies.getAllValues())
        .allSatisfy(b -> assertThat(b).doesNotContain(hr.getEmail(), manager.getEmail(), employee.getFullName()));
  }

  @Test
  void replyAndReplyAllNotifyTheirDerivedRecipients() throws Exception {
    String threadId =
        compose(admin, List.of(hr.getId()), List.of(manager.getId()), List.of(), "Weekly sync")
            .get("threadId")
            .asText();
    Mockito.clearInvocations(push);

    // Reply targets the ORIGINAL SENDER only -> exactly one push, to the admin, keeping the thread subject.
    reply(hr, threadId, "/reply");
    verify(push)
        .sendToPrincipal(
            PrincipalRef.forUser(admin.getId(), null),
            "New message from " + hr.getName(),
            "Weekly sync",
            "/mail");
    verify(push, times(1)).sendToPrincipal(any(), anyString(), anyString(), anyString());
    Mockito.clearInvocations(push);

    // Reply-all from the CC'd manager -> original sender + TO, never the actor.
    reply(manager, threadId, "/reply-all");
    String title = "New message from " + manager.getName();
    verify(push).sendToPrincipal(PrincipalRef.forUser(admin.getId(), null), title, "Weekly sync", "/mail");
    verify(push).sendToPrincipal(PrincipalRef.forUser(hr.getId(), null), title, "Weekly sync", "/mail");
    verify(push, never())
        .sendToPrincipal(Mockito.eq(PrincipalRef.forUser(manager.getId(), null)), anyString(), anyString(), anyString());
    verify(push, times(2)).sendToPrincipal(any(), anyString(), anyString(), anyString());
  }

  @Test
  void aPushFailureNeverAffectsMailDelivery() throws Exception {
    doThrow(new RuntimeException("push service down"))
        .when(push)
        .sendToPrincipal(any(), anyString(), anyString(), anyString());

    JsonNode sent =
        compose(admin, List.of(hr.getId()), List.of(), List.of(), "Still delivered");

    // The mail is committed and fully delivered despite every push blowing up.
    String messageId = sent.get("id").asText();
    assertThat(messages.findById(messageId)).isPresent();
    assertThat(recipients.findByMessageId(messageId)).hasSize(1);
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode compose(
      User sender, List<String> to, List<String> cc, List<String> bcc, String subject)
      throws Exception {
    Map<String, Object> body =
        Map.of(
            "toUserIds", to,
            "ccUserIds", cc,
            "bccUserIds", bcc,
            "subject", subject,
            "body", "Please see the details in the app.");
    return json.readTree(
        mvc.perform(
                post("/mail/messages")
                    .header("Authorization", "Bearer " + token(sender))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void reply(User sender, String threadId, String path) throws Exception {
    mvc.perform(
            post("/mail/threads/" + threadId + path)
                .header("Authorization", "Bearer " + token(sender))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("body", "Following up."))))
        .andExpect(status().isOk());
  }

  private String company(String code, String mailDomain) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    c.setMailDomain(mailDomain);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Employee employee(String companyId, User onboardingHr, String mailAddress) {
    Employee e = new Employee();
    e.setFullName("Arjun Rao");
    e.setEmail("arjun.personal@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(onboardingHr.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    e.setEmployeeCode("ACME-EMP-000001");
    e.setMailLocalPart(mailAddress.split("@")[0]);
    e.setMailAddress(mailAddress);
    e.setPasswordHash("hashed");
    return employees.save(e);
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null));
  }
}
