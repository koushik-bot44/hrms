package com.ihrms.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Internal mail (ARCHITECTURE.md §8) — thread-based (Stage 3). Proves: the ONE send graph
 * ({@code canSendMail}) for both new sends AND replies (403 where forbidden, even after a participant
 * changes company mid-thread); threads (inbox/sent list conversations; a reply joins the thread in
 * chronological order); own-mail-only case-insensitive search; explicit read/unread with a thread-level
 * badge; per-user soft delete (row intact, counterparty unaffected, resurfaces on reply); plus contacts,
 * address formation, and address uniqueness. Runs against a real Postgres (gated on IHRMS_TEST_DB).
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

  // --- Send graph (new sends) -----------------------------------------------

  @Test
  void companyAdminStartsAThreadToHr_landsAsAnUnreadThreadInTheInbox() throws Exception {
    startThread(caA, hrA, "Welcome", "Please onboard the new hire.");
    assertThat(audit("MAIL_SENT")).isEqualTo(1);

    assertThat(unread(hrA)).isEqualTo(1); // one UNREAD THREAD
    JsonNode inbox = inbox(hrA);
    assertThat(inbox.get("totalElements").asInt()).isEqualTo(1);
    JsonNode row = inbox.get("content").get(0);
    assertThat(row.get("participants").get(0).get("address").asText()).isEqualTo("admin@anvicorp");
    assertThat(row.get("subject").asText()).isEqualTo("Welcome");
    assertThat(row.get("messageCount").asInt()).isEqualTo(1);
    assertThat(row.get("unread").asBoolean()).isTrue();
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
    startThread(sa, caA, "s", "b"); // SUPER_ADMIN <-> COMPANY_ADMIN
    startThread(caA, sa, "s", "b");
    startThread(sa, aa, "s", "b"); // SUPER_ADMIN <-> ACCOUNTS_ADMIN
    startThread(aa, sa, "s", "b");
    startThread(caA, hrA, "s", "b"); // COMPANY_ADMIN <-> HR
    startThread(hrA, caA, "s", "b");
    startThread(caA, mgrA, "s", "b"); // COMPANY_ADMIN <-> MANAGER
    startThread(mgrA, caA, "s", "b");
    startThread(caA, accA, "s", "b"); // COMPANY_ADMIN <-> ACCOUNTANT
    startThread(accA, caA, "s", "b");
    assertThat(audit("MAIL_SENT")).isEqualTo(10);
  }

  // --- Contacts mirror the graph exactly ------------------------------------

  @Test
  void contactsAreExactlyTheReachableAccounts() throws Exception {
    assertThat(contactAddresses(caA))
        .containsExactlyInAnyOrder(
            "hr@anvicorp", "hr2@anvicorp", "mgr@anvicorp", "acc@anvicorp", "superadmin@ihrms");
    assertThat(contactAddresses(hrA)).containsExactly("admin@anvicorp");
    assertThat(contactAddresses(sa))
        .containsExactlyInAnyOrder("books@ihrms", "admin@anvicorp", "admin@testco");
    assertThat(contactAddresses(aa)).containsExactly("superadmin@ihrms");
  }

  // --- Threads + reply ------------------------------------------------------

  @Test
  void replyJoinsTheThread_chronological_andReachesTheOtherParticipant() throws Exception {
    String threadId = startThread(caA, hrA, "Onboarding", "Please start.");

    // HR opens the thread: one message, the reply target is the Company Admin.
    JsonNode opened = openThreadJson(hrA, threadId);
    assertThat(opened.get("messages")).hasSize(1);
    assertThat(opened.get("counterparty").get("address").asText()).isEqualTo("admin@anvicorp");

    // HR replies — recipient is DERIVED from the thread (never supplied).
    JsonNode replied = replyOk(hrA, threadId, "On it, thanks.");
    assertThat(replied.get("threadId").asText()).isEqualTo(threadId);
    assertThat(audit("MAIL_SENT")).isEqualTo(2);

    // The reply lands in the Company Admin's inbox, SAME thread, 2 messages, unread.
    JsonNode row = threadRow(inbox(caA), threadId);
    assertThat(row).isNotNull();
    assertThat(row.get("messageCount").asInt()).isEqualTo(2);
    assertThat(row.get("unread").asBoolean()).isTrue();

    // The Company Admin opens it: both messages, chronological, correct senders + mine flags.
    JsonNode caView = openThreadJson(caA, threadId);
    assertThat(caView.get("messages")).hasSize(2);
    assertThat(caView.get("messages").get(0).get("from").get("address").asText())
        .isEqualTo("admin@anvicorp");
    assertThat(caView.get("messages").get(0).get("mine").asBoolean()).isTrue();
    assertThat(caView.get("messages").get(1).get("from").get("address").asText())
        .isEqualTo("hr@anvicorp");
    assertThat(caView.get("messages").get(1).get("mine").asBoolean()).isFalse();
    assertThat(caView.get("counterparty").get("address").asText()).isEqualTo("hr@anvicorp");
  }

  @Test
  void replyIsGraphValidated_forbiddenWhenAParticipantMovesCompany() throws Exception {
    String threadId = startThread(caA, hrA, "Hello", "Hi there.");

    // HR is reassigned to another company AFTER the thread started. A reply is a send — the graph
    // re-checks it, and HR<->anvi Company Admin is now cross-company.
    hrA.setCompanyId(testco);
    users.saveAndFlush(hrA);

    reply(hrA, threadId, "Trying to reply.").andExpect(status().isForbidden());
    assertThat(audit("MAIL_SENT")).isEqualTo(1); // only the original send
  }

  @Test
  void aNonParticipantCannotOpenAThread() throws Exception {
    String threadId = startThread(caA, hrA, "Private", "Between us.");
    mvc.perform(get("/mail/threads/" + threadId).header("Authorization", "Bearer " + token(mgrA)))
        .andExpect(status().isForbidden());
  }

  @Test
  void sentListsThreadsWithTheRecipient() throws Exception {
    String threadId = startThread(caA, hrA, "Note", "A note.");
    JsonNode row = threadRow(sent(caA), threadId);
    assertThat(row).isNotNull();
    assertThat(row.get("participants").get(0).get("address").asText()).isEqualTo("hr@anvicorp");
    assertThat(row.get("messageCount").asInt()).isEqualTo(1);
  }

  // --- Search (own mail only, subject + body, case-insensitive) -------------

  @Test
  void searchIsOwnMailOnly_matchesSubjectAndBody_caseInsensitive() throws Exception {
    startThread(caA, hrA, "Payroll", "The payroll run details are attached.");
    startThread(caA, mgrA, "Budget", "Quarterly budget sync.");

    // HR is a participant of the Payroll thread only.
    assertThat(searchCount(hrA, "payroll")).isEqualTo(1); // subject
    assertThat(searchCount(hrA, "PAYROLL")).isEqualTo(1); // case-insensitive
    assertThat(searchCount(hrA, "run")).isEqualTo(1); // body match
    assertThat(searchCount(hrA, "budget")).isZero(); // NOT HR's mail — no cross-mailbox leak
    assertThat(searchCount(mgrA, "payroll")).isZero(); // NOT the Manager's mail either

    // The Company Admin is a participant of both.
    assertThat(searchCount(caA, "payroll")).isEqualTo(1);
    assertThat(searchCount(caA, "budget")).isEqualTo(1);
  }

  // --- Read state (explicit + thread-level badge) ---------------------------

  @Test
  void markReadAndUnreadMoveTheThreadBadge() throws Exception {
    String threadId = startThread(caA, hrA, "Ping", "Ping body.");
    assertThat(unread(hrA)).isEqualTo(1);

    // Opening marks read.
    openThreadJson(hrA, threadId);
    assertThat(unread(hrA)).isZero();

    // Explicit unread -> badge back to 1; explicit read -> 0. The endpoints return the fresh count.
    assertThat(markUnread(hrA, threadId).get("unread").asLong()).isEqualTo(1);
    assertThat(unread(hrA)).isEqualTo(1);
    assertThat(markRead(hrA, threadId).get("unread").asLong()).isZero();
    assertThat(unread(hrA)).isZero();
  }

  // --- Delete (per-user soft-hide) ------------------------------------------

  @Test
  void deleteHidesTheThreadForMeOnly_rowIntact_andResurfacesOnReply() throws Exception {
    String threadId = startThread(caA, hrA, "Notice", "Notice body.");

    mvc.perform(delete("/mail/threads/" + threadId).header("Authorization", "Bearer " + token(hrA)))
        .andExpect(status().isNoContent());
    assertThat(audit("MAIL_DELETED")).isEqualTo(1);

    // Gone from HR's inbox...
    assertThat(inbox(hrA).get("totalElements").asInt()).isZero();
    assertThat(unread(hrA)).isZero();
    // ...but the Company Admin still has the whole conversation.
    assertThat(threadRow(sent(caA), threadId)).isNotNull();
    mvc.perform(get("/mail/threads/" + threadId).header("Authorization", "Bearer " + token(caA)))
        .andExpect(status().isOk());

    // The message row is NOT destroyed — only HR's recipient copy is soft-hidden.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM \"messages\" WHERE \"threadId\" = ?", Long.class, threadId))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM \"message_recipients\" r JOIN \"messages\" m ON m.\"id\" = r.\"messageId\""
                    + " WHERE m.\"threadId\" = ? AND r.\"deletedAt\" IS NOT NULL",
                Long.class,
                threadId))
        .isEqualTo(1L);

    // A new reply from the Company Admin resurfaces the thread for HR (only the un-hidden message shows).
    replyOk(caA, threadId, "Following up.");
    JsonNode row = threadRow(inbox(hrA), threadId);
    assertThat(row).isNotNull();
    assertThat(row.get("messageCount").asInt()).isEqualTo(1); // the original is still hidden from HR
    assertThat(row.get("unread").asBoolean()).isTrue();
  }

  // --- Employees are excluded from mail -------------------------------------

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

  // --- Address formation + uniqueness (unchanged from Stage 2) --------------

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
    assertThat(admin.get("email").asText()).isEqualTo("bella@betacorp");
    assertThat(users.findByEmail("bella@betacorp").orElseThrow().getMailLocalPart()).isEqualTo("bella");
  }

  @Test
  void anAddressIsUniqueWithinADomainButFreeAcrossDomains() {
    users.saveAndFlush(newUser(anvi, UserRole.HR, "sales@anvicorp", "sales"));
    users.saveAndFlush(newUser(testco, UserRole.HR, "sales@testco", "sales"));
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
  private ResultActions send(User from, User to) throws Exception {
    return mvc.perform(
        post("/mail/messages")
            .header("Authorization", "Bearer " + token(from))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("toUserId", to.getId(), "subject", "s", "body", "b"))));
  }

  /** Start a new thread (expects 200); returns the threadId. */
  private String startThread(User from, User to, String subject, String body) throws Exception {
    String out =
        mvc.perform(
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
    return json.readTree(out).get("threadId").asText();
  }

  private ResultActions reply(User who, String threadId, String body) throws Exception {
    return mvc.perform(
        post("/mail/threads/" + threadId + "/reply")
            .header("Authorization", "Bearer " + token(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("body", body))));
  }

  private JsonNode replyOk(User who, String threadId, String body) throws Exception {
    return json.readTree(
        reply(who, threadId, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private JsonNode openThreadJson(User who, String threadId) throws Exception {
    return getJson(who, "/mail/threads/" + threadId);
  }

  private JsonNode markRead(User who, String threadId) throws Exception {
    return postJson(who, "/mail/threads/" + threadId + "/read");
  }

  private JsonNode markUnread(User who, String threadId) throws Exception {
    return postJson(who, "/mail/threads/" + threadId + "/unread");
  }

  private JsonNode inbox(User who) throws Exception {
    return getJson(who, "/mail/inbox");
  }

  private JsonNode sent(User who) throws Exception {
    return getJson(who, "/mail/sent");
  }

  private long searchCount(User who, String q) throws Exception {
    return getJson(who, "/mail/search?q=" + q).get("totalElements").asLong();
  }

  private long unread(User who) throws Exception {
    return getJson(who, "/mail/unread-count").get("unread").asLong();
  }

  /** Find a thread row in a ThreadPage by threadId, or null. */
  private JsonNode threadRow(JsonNode page, String threadId) {
    for (JsonNode row : page.get("content")) {
      if (row.get("threadId").asText().equals(threadId)) {
        return row;
      }
    }
    return null;
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

  private JsonNode postJson(User who, String path) throws Exception {
    return json.readTree(
        mvc.perform(post(path).header("Authorization", "Bearer " + token(who)))
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
