package com.ihrms.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Internal mail (ARCHITECTURE.md §8). Proves the ONE send graph ({@code canSendMail}) end-to-end over
 * HTTP — symmetric where allowed, never cross-company, employees excluded — plus inbox/sent/read state,
 * contacts, unread-count, address formation ({@code localpart@domain}), and address uniqueness within a
 * domain. Runs in {@code ./mvnw package} against a real Postgres (gated on IHRMS_TEST_DB).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class MailApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;

  private String anvi; // company, mailDomain "anvicorp"
  private String testco; // company, mailDomain "testco"

  private User sa; // SUPER_ADMIN     superadmin@ihrms
  private User aa; // ACCOUNTS_ADMIN  books@ihrms
  private User caA; // COMPANY_ADMIN  admin@anvicorp
  private User hrA; // HR             hr@anvicorp
  private User hr2A; // HR            hr2@anvicorp
  private User mgrA; // MANAGER       mgr@anvicorp
  private User accA; // ACCOUNTANT    acc@anvicorp
  private User caT; // COMPANY_ADMIN  admin@testco (other company)
  private User hrT; // HR             hr@testco    (other company)

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"companies\",\"messages\",\"message_recipients\",\"audit_logs\""
            + " RESTART IDENTITY CASCADE");
    anvi = company("ANVI", "anvicorp");
    testco = company("TESTCO", "testco");

    sa = user(null, UserRole.SUPER_ADMIN, "superadmin@ihrms", "superadmin");
    aa = user(null, UserRole.ACCOUNTS_ADMIN, "books@ihrms", "books");
    caA = user(anvi, UserRole.COMPANY_ADMIN, "admin@anvicorp", "admin");
    hrA = user(anvi, UserRole.HR, "hr@anvicorp", "hr");
    hr2A = user(anvi, UserRole.HR, "hr2@anvicorp", "hr2");
    mgrA = user(anvi, UserRole.MANAGER, "mgr@anvicorp", "mgr");
    accA = user(anvi, UserRole.ACCOUNTANT, "acc@anvicorp", "acc");
    caT = user(testco, UserRole.COMPANY_ADMIN, "admin@testco", "admin");
    hrT = user(testco, UserRole.HR, "hr@testco", "hr");
  }

  // --- Send graph -----------------------------------------------------------

  @Test
  void companyAdminMessagesSameCompanyHr_andItLandsUnreadInTheInbox() throws Exception {
    JsonNode sent = json.readTree(sendOk(caA, hrA, "Welcome", "Please onboard the new hire."));
    assertThat(sent.get("id").asText()).isNotBlank();

    // One MAIL_SENT audit event, attributed to the sender's company.
    assertThat(audit("MAIL_SENT")).isEqualTo(1);

    assertThat(unread(hrA)).isEqualTo(1);
    JsonNode inbox = inbox(hrA);
    assertThat(inbox.get("totalElements").asInt()).isEqualTo(1);
    JsonNode row = inbox.get("content").get(0);
    assertThat(row.get("from").get("address").asText()).isEqualTo("admin@anvicorp");
    assertThat(row.get("read").asBoolean()).isFalse();
  }

  @Test
  void crossCompanyIsAlwaysForbidden() throws Exception {
    send(caA, hrT).andExpect(status().isForbidden()); // anvi admin -> testco HR
    send(caT, hrA).andExpect(status().isForbidden()); // testco admin -> anvi HR
    assertThat(audit("MAIL_SENT")).isZero();
  }

  @Test
  void peersAndOffGraphPairsAreForbidden() throws Exception {
    send(hrA, hr2A).andExpect(status().isForbidden()); // HR -> HR (peer)
    send(hrA, mgrA).andExpect(status().isForbidden()); // HR -> MANAGER (not an edge)
    send(caA, aa).andExpect(status().isForbidden()); // COMPANY_ADMIN -> ACCOUNTS_ADMIN (not an edge)
    send(hrA, sa).andExpect(status().isForbidden()); // HR -> SUPER_ADMIN (not an edge)
    assertThat(audit("MAIL_SENT")).isZero();
  }

  @Test
  void allowedEdgesWorkInBothDirections() throws Exception {
    sendOk(sa, caA, "s", "b"); // SUPER_ADMIN <-> COMPANY_ADMIN
    sendOk(caA, sa, "s", "b");
    sendOk(sa, aa, "s", "b"); // SUPER_ADMIN <-> ACCOUNTS_ADMIN
    sendOk(aa, sa, "s", "b");
    sendOk(caA, hrA, "s", "b"); // COMPANY_ADMIN <-> HR
    sendOk(hrA, caA, "s", "b");
    sendOk(caA, mgrA, "s", "b"); // COMPANY_ADMIN <-> MANAGER
    sendOk(mgrA, caA, "s", "b");
    sendOk(caA, accA, "s", "b"); // COMPANY_ADMIN <-> ACCOUNTANT
    sendOk(accA, caA, "s", "b");
    assertThat(audit("MAIL_SENT")).isEqualTo(10);
  }

  // --- Contacts mirror the graph exactly ------------------------------------

  @Test
  void contactsAreExactlyTheReachableAccounts() throws Exception {
    // COMPANY_ADMIN: its own company's staff + SUPER_ADMIN — NOT the Accounts Admin, NOT other companies.
    assertThat(contactAddresses(caA))
        .containsExactlyInAnyOrder(
            "hr@anvicorp", "hr2@anvicorp", "mgr@anvicorp", "acc@anvicorp", "superadmin@ihrms");

    // HR: only its Company Admin.
    assertThat(contactAddresses(hrA)).containsExactly("admin@anvicorp");

    // SUPER_ADMIN: the Accounts Admin + every company's admin (cross-company is allowed for this edge).
    assertThat(contactAddresses(sa))
        .containsExactlyInAnyOrder("books@ihrms", "admin@anvicorp", "admin@testco");

    // ACCOUNTS_ADMIN: only the Super Admin.
    assertThat(contactAddresses(aa)).containsExactly("superadmin@ihrms");
  }

  // --- Read flow: read_at, unread-count, sent, and view authorization -------

  @Test
  void openingAMessageMarksItReadForTheRecipientOnly_andSenderMayReadWithoutAffectingIt()
      throws Exception {
    String id = json.readTree(sendOk(caA, hrA, "Subject", "The body")).get("id").asText();

    // Sender's "sent" shows the recipient by address.
    JsonNode sentList = sent(caA);
    assertThat(sentList.get("totalElements").asInt()).isEqualTo(1);
    assertThat(sentList.get("content").get(0).get("to").get(0).get("address").asText())
        .isEqualTo("hr@anvicorp");

    // A non-participant cannot open it.
    view(mgrA, id).andExpect(status().isForbidden());

    // The recipient opens it: 200, full body, now read + audited.
    JsonNode opened =
        json.readTree(
            view(hrA, id)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(opened.get("body").asText()).isEqualTo("The body");
    assertThat(opened.get("read").asBoolean()).isTrue();
    assertThat(audit("MAIL_VIEWED")).isEqualTo(1);
    assertThat(unread(hrA)).isZero();
    assertThat(inbox(hrA).get("content").get(0).get("read").asBoolean()).isTrue();

    // The sender may also read it (as sender) — this does not resurrect the recipient's unread state.
    view(caA, id).andExpect(status().isOk());
    assertThat(unread(hrA)).isZero();
  }

  // --- Employees are excluded from mail at this stage -----------------------

  @Test
  void employeesCannotReachTheMailbox() throws Exception {
    String empToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee("emp-1", "ANVI-EMP-000001", "e@anvi.test", anvi));
    mvc.perform(get("/mail/contacts").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/mail/inbox").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isForbidden());
  }

  // --- Address formation from a provisioned local part ----------------------

  @Test
  void provisioningFormsTheAddressFromLocalPartAndCompanyDomain() throws Exception {
    String superToken = token(sa);
    String companyId =
        json.readTree(
                mvc.perform(
                        post("/companies")
                            .header("Authorization", "Bearer " + superToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                json.writeValueAsString(
                                    Map.of("name", "Beta Corp", "code", "BETA", "mailDomain", "betacorp"))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();

    JsonNode admin =
        json.readTree(
                mvc.perform(
                        post("/companies/" + companyId + "/admin")
                            .header("Authorization", "Bearer " + superToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                json.writeValueAsString(
                                    Map.of(
                                        "name", "Bella Admin",
                                        "localPart", "bella",
                                        "password", "Passw0rd!"))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("admin");
    // localPart + companyDomain -> the address, which IS the login email.
    assertThat(admin.get("email").asText()).isEqualTo("bella@betacorp");
    assertThat(users.findByEmail("bella@betacorp").orElseThrow().getMailLocalPart()).isEqualTo("bella");
  }

  // --- Uniqueness of localpart@domain (the address IS the unique login email) --

  @Test
  void anAddressIsUniqueWithinADomainButFreeAcrossDomains() {
    // Same local part, DIFFERENT domain -> two distinct addresses, both fine.
    users.saveAndFlush(newUser(anvi, UserRole.HR, "sales@anvicorp", "sales"));
    users.saveAndFlush(newUser(testco, UserRole.HR, "sales@testco", "sales"));

    // Same local part, SAME domain -> same address -> rejected by the unique index.
    assertThatThrownBy(() -> users.saveAndFlush(newUser(anvi, UserRole.MANAGER, "sales@anvicorp", "sales")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- helpers --------------------------------------------------------------

  private String company(String code, String mailDomain) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    c.setMailDomain(mailDomain);
    return companies.save(c).getId();
  }

  private User newUser(String companyId, UserRole role, String email, String localPart) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setMailLocalPart(localPart);
    return u;
  }

  private User user(String companyId, UserRole role, String email, String localPart) {
    return users.save(newUser(companyId, role, email, localPart));
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null));
  }

  /** POST /mail/messages from -> to (no status assertion). */
  private org.springframework.test.web.servlet.ResultActions send(User from, User to) throws Exception {
    return mvc.perform(
        post("/mail/messages")
            .header("Authorization", "Bearer " + token(from))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of("toUserId", to.getId(), "subject", "s", "body", "b"))));
  }

  /** Send expecting 200; returns the response body (the SendMessageResult). */
  private String sendOk(User from, User to, String subject, String body) throws Exception {
    return mvc.perform(
            post("/mail/messages")
                .header("Authorization", "Bearer " + token(from))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("toUserId", to.getId(), "subject", subject, "body", body))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private org.springframework.test.web.servlet.ResultActions view(User who, String id) throws Exception {
    return mvc.perform(get("/mail/messages/" + id).header("Authorization", "Bearer " + token(who)));
  }

  private JsonNode inbox(User who) throws Exception {
    return getJson(who, "/mail/inbox");
  }

  private JsonNode sent(User who) throws Exception {
    return getJson(who, "/mail/sent");
  }

  private long unread(User who) throws Exception {
    return getJson(who, "/mail/unread-count").get("unread").asLong();
  }

  private Set<String> contactAddresses(User who) throws Exception {
    JsonNode arr = getJson(who, "/mail/contacts");
    Set<String> out = new HashSet<>();
    arr.forEach(n -> out.add(n.get("address").asText()));
    return out;
  }

  private JsonNode getJson(User who, String path) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("Authorization", "Bearer " + token(who)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private long audit(String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM \"audit_logs\" WHERE \"action\" = ?", Long.class, action);
  }
}
