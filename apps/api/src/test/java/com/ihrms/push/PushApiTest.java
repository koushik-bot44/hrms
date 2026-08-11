package com.ihrms.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.ihrms.domain.model.PushSubscription;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.PushSubscriptionRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web Push N1: subscription storage + the /push endpoints. The push CLIENT is disabled here (no VAPID in
 * the test env — the app boots with push off, since fail-fast is prod-only), so this covers subscription
 * upsert/dedupe/authz/audit + the prune-on-gone policy; REAL delivery is the documented manual path.
 * A fake PUBLIC key is injected to exercise /push/public-key (the public key is not a secret).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.vapid.public-key=test-public-key-not-a-real-vapid-key")
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class PushApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired PushSubscriptionRepository subscriptions;
  @Autowired AuditLogRepository auditLogs;
  @Autowired PushService push;
  @Autowired JdbcTemplate jdbc;

  private String acme;
  private User hr;
  private Employee credentialed;
  private Employee uncredentialed;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"push_subscriptions\",\"employees\",\"users\",\"companies\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");
    hr = user(acme, UserRole.HR, "hr@acme");
    credentialed = employee(acme, hr, "arjun@acme"); // has a mailbox
    uncredentialed = employee(acme, hr, null); // OTP-only, no mailbox
  }

  @Test
  void subscribeUpsertsAndDedupesOnEndpoint() throws Exception {
    String endpoint = "https://push.example.com/ep/AAA";

    JsonNode first = subscribe(token(hr), endpoint, "keyOne", "authOne", null);
    assertThat(first.get("existing").asBoolean()).isFalse();
    assertThat(subscriptions.count()).isEqualTo(1);

    // Same endpoint again (new keys) -> UPDATE, not a second row.
    JsonNode again = subscribe(token(hr), endpoint, "keyTwo", "authTwo", "Firefox");
    assertThat(again.get("existing").asBoolean()).isTrue();
    assertThat(subscriptions.count()).isEqualTo(1);

    PushSubscription saved = subscriptions.findByEndpoint(endpoint).orElseThrow();
    assertThat(saved.getP256dh()).isEqualTo("keyTwo");
    assertThat(saved.getAuth()).isEqualTo("authTwo");
    assertThat(saved.getUserId()).isEqualTo(hr.getId());
    assertThat(saved.getEmployeeId()).isNull();
    assertThat(auditLogs.findByAction("PUSH_SUBSCRIBED")).hasSize(2);
  }

  @Test
  void unsubscribeRemovesTheCallersSubscription() throws Exception {
    String endpoint = "https://push.example.com/ep/BBB";
    subscribe(token(hr), endpoint, "k", "a", null);
    assertThat(subscriptions.count()).isEqualTo(1);

    mvc.perform(
            delete("/push/unsubscribe")
                .header("Authorization", "Bearer " + token(hr))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endpoint", endpoint))))
        .andExpect(status().isNoContent());
    assertThat(subscriptions.count()).isZero();
    assertThat(auditLogs.findByAction("PUSH_UNSUBSCRIBED")).hasSize(1);

    // Unsubscribing an unknown endpoint is idempotent (still 204).
    mvc.perform(
            delete("/push/unsubscribe")
                .header("Authorization", "Bearer " + token(hr))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endpoint", endpoint))))
        .andExpect(status().isNoContent());
  }

  @Test
  void onlyMailboxCapablePrincipalsMaySubscribe() throws Exception {
    // A credentialed employee CAN subscribe.
    subscribe(empToken(credentialed), "https://push.example.com/ep/EMP", "k", "a", null);
    assertThat(subscriptions.findByEmployeeId(credentialed.getId())).hasSize(1);

    // An uncredentialed (OTP-only) employee CANNOT -> 403.
    mvc.perform(
            post("/push/subscribe")
                .header("Authorization", "Bearer " + empToken(uncredentialed))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("https://push.example.com/ep/NOPE", "k", "a", null)))
        .andExpect(status().isForbidden());
  }

  @Test
  void publicKeyReflectsServerConfig() throws Exception {
    JsonNode res =
        json.readTree(
            mvc.perform(get("/push/public-key").header("Authorization", "Bearer " + token(hr)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(res.get("publicKey").asText()).isEqualTo("test-public-key-not-a-real-vapid-key");
    // Only the public key is set (no private key) -> the sender stays disabled.
    assertThat(res.get("enabled").asBoolean()).isFalse();
  }

  @Test
  void testEndpointIsBestEffortWhenPushDisabled() throws Exception {
    subscribe(token(hr), "https://push.example.com/ep/CCC", "k", "a", null);
    // Push is not configured in tests -> /push/test does NOT throw; it reports it's off.
    JsonNode res =
        json.readTree(
            mvc.perform(post("/push/test").header("Authorization", "Bearer " + token(hr)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(res.get("targeted").asInt()).isZero();
  }

  @Test
  void aGoneEndpointIsPrunedByHandleStatus() {
    // The prune policy that runs when the push service reports the subscription is gone.
    PushSubscription sub = new PushSubscription();
    sub.setUserId(hr.getId());
    sub.setCompanyId(acme);
    sub.setEndpoint("https://push.example.com/ep/GONE");
    sub.setP256dh("k");
    sub.setAuth("a");
    sub = subscriptions.save(sub);

    push.handleStatus(sub, 410); // 410 Gone
    assertThat(subscriptions.findByEndpoint("https://push.example.com/ep/GONE")).isEmpty();
  }

  @Test
  void staleSubscriptionsArePrunedByAge() throws Exception {
    // The origin isn't stored, so old rows (e.g. a pre-migration *.vercel.app origin) can only be cleared by
    // age. Seed one old (10 days) + one fresh, then prune anything older than 7 days.
    seedSub("https://push.example.com/ep/OLD");
    seedSub("https://push.example.com/ep/NEW");
    jdbc.update(
        "UPDATE \"push_subscriptions\" SET \"createdAt\" = now() - interval '10 days' WHERE \"endpoint\" = ?",
        "https://push.example.com/ep/OLD");
    assertThat(subscriptions.count()).isEqualTo(2);

    User superAdmin = superAdmin();
    JsonNode res =
        json.readTree(
            mvc.perform(
                    post("/push/admin/prune-stale?olderThanDays=7")
                        .header("Authorization", "Bearer " + token(superAdmin)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(res.get("pruned").asInt()).isEqualTo(1);

    assertThat(subscriptions.findByEndpoint("https://push.example.com/ep/OLD")).isEmpty();
    assertThat(subscriptions.findByEndpoint("https://push.example.com/ep/NEW")).isPresent();
    assertThat(auditLogs.findByAction("PUSH_SUBSCRIPTIONS_PRUNED")).hasSize(1);
  }

  @Test
  void pruneStaleIsSuperAdminOnly() throws Exception {
    // A company HR must not be able to run the platform-wide prune.
    mvc.perform(post("/push/admin/prune-stale").header("Authorization", "Bearer " + token(hr)))
        .andExpect(status().isForbidden());
  }

  // --- helpers --------------------------------------------------------------

  private void seedSub(String endpoint) {
    PushSubscription s = new PushSubscription();
    s.setUserId(hr.getId());
    s.setCompanyId(acme);
    s.setEndpoint(endpoint);
    s.setP256dh("k");
    s.setAuth("a");
    subscriptions.save(s);
  }

  private User superAdmin() {
    User u = new User();
    u.setEmail("root@platform");
    u.setName("Root");
    u.setRole(UserRole.SUPER_ADMIN);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private JsonNode subscribe(String token, String endpoint, String p256dh, String auth, String ua)
      throws Exception {
    return json.readTree(
        mvc.perform(
                post("/push/subscribe")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(endpoint, p256dh, auth, ua)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String body(String endpoint, String p256dh, String auth, String ua) throws Exception {
    Map<String, Object> b =
        ua == null
            ? Map.of("endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", auth))
            : Map.of(
                "endpoint", endpoint, "keys", Map.of("p256dh", p256dh, "auth", auth), "userAgent", ua);
    return json.writeValueAsString(b);
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
    e.setFullName("Emp " + (mailAddress == null ? "nomail" : mailAddress.split("@")[0]));
    e.setEmail("personal-" + UUID.randomUUID() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(onboardingHr.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    e.setEmployeeCode("ACME-EMP-" + (int) (Math.random() * 1_000_000));
    if (mailAddress != null) {
      e.setMailLocalPart(mailAddress.split("@")[0]);
      e.setMailAddress(mailAddress);
      e.setPasswordHash("hashed");
    }
    return employees.save(e);
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null));
  }

  private String empToken(Employee e) {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee(
            e.getId(), e.getEmployeeCode(), e.getEmail(), e.getCompanyId(), e.getFullName(), e.getMailAddress()));
  }
}
