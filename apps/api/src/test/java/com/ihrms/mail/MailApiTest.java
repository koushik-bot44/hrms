package com.ihrms.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
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
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired JdbcTemplate jdbc;

  // Storage is mocked so the presigned handshake runs without a real bucket: the key is derived from the
  // file name, and getObjectBytes returns bytes so bind can hash them. (The real S3/db roundtrip is
  // covered by the storage tests + the live walk.)
  @MockBean com.ihrms.storage.StorageService storage;

  private String anvi; // company, mailDomain "anvicorp"
  private String testco; // company, mailDomain "testco"

  private User sa; // SUPER_ADMIN     superadmin@ihrms
  private User aa; // ACCOUNTS_ADMIN  books@ihrms
  private User caA; // COMPANY_ADMIN  admin@anvicorp
  private User hrA; // HR    hr@anvicorp   — TEAM A
  private User mgrA; // MANAGER mgr@anvicorp — TEAM A
  private User hr2A; // HR    hr2@anvicorp  — TEAM B
  private User mgr2A; // MANAGER mgr2@anvicorp — TEAM B
  private User accA; // ACCOUNTANT acc@anvicorp — TEAM A's accountant (NOT in team mail)
  private User caT; // COMPANY_ADMIN  admin@testco (other company)
  private User hrT; // HR             hr@testco    (other company)

  // Team A employees (onboarded by hrA) + a Team B employee (onboarded by hr2A).
  private Employee empA1; // arjun@anvicorp
  private Employee empA2; // meera@anvicorp
  private Employee empB1; // bhavya@anvicorp

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"messages\","
            + "\"message_recipients\",\"message_attachments\",\"thread_stars\",\"audit_logs\""
            + " RESTART IDENTITY CASCADE");
    anvi = company("ANVI", "anvicorp");
    testco = company("TESTCO", "testco");

    sa = user(null, UserRole.SUPER_ADMIN, "superadmin@ihrms", "superadmin");
    aa = user(null, UserRole.ACCOUNTS_ADMIN, "books@ihrms", "books");
    caA = user(anvi, UserRole.COMPANY_ADMIN, "admin@anvicorp", "admin");
    hrA = user(anvi, UserRole.HR, "hr@anvicorp", "hr");
    mgrA = user(anvi, UserRole.MANAGER, "mgr@anvicorp", "mgr");
    hr2A = user(anvi, UserRole.HR, "hr2@anvicorp", "hr2");
    mgr2A = user(anvi, UserRole.MANAGER, "mgr2@anvicorp", "mgr2");
    accA = user(anvi, UserRole.ACCOUNTANT, "acc@anvicorp", "acc");
    caT = user(testco, UserRole.COMPANY_ADMIN, "admin@testco", "admin");
    hrT = user(testco, UserRole.HR, "hr@testco", "hr");

    // Team A = { hrA, mgrA, accountant accA }; Team B = { hr2A, mgr2A }.
    team(anvi, hrA, mgrA, accA);
    team(anvi, hr2A, mgr2A, null);

    empA1 = employee(anvi, hrA, "arjun@anvicorp"); // Team A
    empA2 = employee(anvi, hrA, "meera@anvicorp"); // Team A
    empB1 = employee(anvi, hr2A, "bhavya@anvicorp"); // Team B

    // Storage stubs: key = "mail/att/<fileName>"; every stored object is small unless a test overrides
    // getObjectBytes for a specific key (used to prove bind re-checks the ACTUAL size).
    when(storage.buildMailAttachmentKey(any(), any()))
        .thenAnswer(inv -> "mail/att/" + inv.getArgument(1));
    when(storage.presignedPutUrl(any(), any(), anyInt())).thenReturn("http://storage.local/put");
    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://storage.local/get?sig=x");
    when(storage.getObjectBytes(any())).thenReturn("hello world".getBytes());
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
    sendExpect(empToken(empA1), hrT.getId(), status().isForbidden()); // anvi employee -> testco HR
    assertThat(audit("MAIL_SENT")).isZero();
  }

  @Test
  void offGraphAndAccountantPairsAreForbidden() throws Exception {
    send(hrA, hr2A).andExpect(status().isForbidden()); // HR <-> HR on a DIFFERENT team
    send(hrA, mgr2A).andExpect(status().isForbidden()); // HR <-> a DIFFERENT team's Manager
    send(caA, aa).andExpect(status().isForbidden()); // COMPANY_ADMIN <-> ACCOUNTS_ADMIN (not an edge)
    send(hrA, sa).andExpect(status().isForbidden()); // HR <-> SUPER_ADMIN (not an edge)
    // The Accountant connects ONLY to its OWN team's employees + its Company Admin (§8d) — NOT the team's
    // HR/Manager, NOT the platform, and NOT a DIFFERENT team's employee.
    send(accA, hrA).andExpect(status().isForbidden()); // ACCOUNTANT <-> its team's HR
    send(accA, mgrA).andExpect(status().isForbidden()); // ACCOUNTANT <-> its team's Manager
    send(accA, sa).andExpect(status().isForbidden()); // ACCOUNTANT <-> SUPER_ADMIN
    sendExpect(empToken(empB1), accA.getId(), status().isForbidden()); // a DIFFERENT team's employee
    sendExpect(token(accA), empB1.getId(), status().isForbidden()); // ACCOUNTANT <-> other-team employee
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
    startThread(caA, accA, "s", "b"); // COMPANY_ADMIN <-> ACCOUNTANT
    startThread(accA, caA, "s", "b");
    startThread(hrA, mgrA, "s", "b"); // TEAM: HR <-> its Manager
    startThread(mgrA, hrA, "s", "b");
    assertThat(audit("MAIL_SENT")).isEqualTo(10);
  }

  @Test
  void theTeamAccountantEdgeIsExactlyEmployeeAndAccountant() throws Exception {
    // §8d — the ONLY new edge: an EMPLOYEE and the ACCOUNTANT of their OWN team, symmetric, same company.
    sendExpect(empToken(empA1), accA.getId(), status().isOk()); // team-A employee -> team-A Accountant
    sendExpect(token(accA), empA1.getId(), status().isOk()); // team-A Accountant -> team-A employee
    sendExpect(token(accA), empA2.getId(), status().isOk()); // ...and its other team-A employee
    sendExpect(token(accA), caA.getId(), status().isOk()); // the Accountant <-> Company Admin still works
    assertThat(audit("MAIL_SENT")).isEqualTo(4);

    // The edge did NOT connect the Accountant to the team's HR/Manager, another team's employee, another
    // company, or the platform.
    sendExpect(token(accA), hrA.getId(), status().isForbidden());
    sendExpect(token(accA), mgrA.getId(), status().isForbidden());
    sendExpect(token(accA), empB1.getId(), status().isForbidden()); // a DIFFERENT team's employee
    sendExpect(token(accA), hrT.getId(), status().isForbidden()); // cross-company
    sendExpect(token(accA), sa.getId(), status().isForbidden());
    assertThat(audit("MAIL_SENT")).isEqualTo(4); // no new sends from the denied attempts
  }

  // --- Contacts mirror the graph exactly ------------------------------------

  @Test
  void contactsAreExactlyTheReachableAccounts() throws Exception {
    // Company Admin: everyone in the company (all staff + all credentialed employees) + Super Admin.
    assertThat(contactAddresses(caA))
        .containsExactlyInAnyOrder(
            "hr@anvicorp", "hr2@anvicorp", "mgr@anvicorp", "mgr2@anvicorp", "acc@anvicorp",
            "arjun@anvicorp", "meera@anvicorp", "bhavya@anvicorp", "superadmin@ihrms");
    // HR: its team (its employees + its Manager) + the Company Admin. Not the accountant, not team B.
    assertThat(contactAddresses(hrA))
        .containsExactlyInAnyOrder("mgr@anvicorp", "arjun@anvicorp", "meera@anvicorp", "admin@anvicorp");
    // Manager: its team (the HR + that HR's employees) + the Company Admin.
    assertThat(contactAddresses(mgrA))
        .containsExactlyInAnyOrder("hr@anvicorp", "arjun@anvicorp", "meera@anvicorp", "admin@anvicorp");
    // Employee: their teammates (HR, Manager, other employees), their team Accountant (§8d), + Company Admin.
    assertThat(contactAddrs(empToken(empA1)))
        .containsExactlyInAnyOrder(
            "hr@anvicorp", "mgr@anvicorp", "meera@anvicorp", "acc@anvicorp", "admin@anvicorp");
    // Accountant: the employees of its OWN team (§8d) + the Company Admin. NOT the team's HR/Manager, not team B.
    assertThat(contactAddresses(accA))
        .containsExactlyInAnyOrder("arjun@anvicorp", "meera@anvicorp", "admin@anvicorp");
    // Platform roles unchanged.
    assertThat(contactAddresses(sa))
        .containsExactlyInAnyOrder("books@ihrms", "admin@anvicorp", "admin@testco");
    assertThat(contactAddresses(aa)).containsExactly("superadmin@ihrms");
  }

  // --- CC / BCC + Reply / Reply All -----------------------------------------

  @Test
  void sendWithToCcBcc_allAllowed_isDeliveredToEveryone() throws Exception {
    // Company Admin may mail anyone in the company, so TO hr, CC mgr, BCC accountant are all allowed.
    compose(caA, List.of(hrA.getId()), List.of(mgrA.getId()), List.of(accA.getId()), "All hands", "Read this.");
    assertThat(unread(hrA)).isEqualTo(1);
    assertThat(unread(mgrA)).isEqualTo(1);
    assertThat(unread(accA)).isEqualTo(1); // the BCC recipient IS delivered the message
  }

  @Test
  void permissionBackstop_aDisallowedCcOrBccRejectsTheWholeSend() throws Exception {
    // HR may mail its own employee (TO), but not another team's HR (CC) — the whole send is rejected.
    composeRaw(token(hrA), List.of(empA1.getId()), List.of(hr2A.getId()), List.of())
        .andExpect(status().isForbidden());
    // ...nor the team's Accountant as a BCC.
    composeRaw(token(hrA), List.of(empA1.getId()), List.of(), List.of(accA.getId()))
        .andExpect(status().isForbidden());
    assertThat(audit("MAIL_SENT")).isZero(); // nothing was delivered
  }

  @Test
  void bccPrivacy_visibleRecipientsAreComputedPerViewer() throws Exception {
    String threadId =
        compose(caA, List.of(hrA.getId()), List.of(mgrA.getId()), List.of(accA.getId()), "Notice", "Hi.");

    // A TO recipient (hrA) and a CC recipient (mgrA) see To+Cc and NO Bcc at all — the response to them
    // must not contain the BCC's identity ANYWHERE (not just hidden in the UI).
    for (User viewer : List.of(hrA, mgrA)) {
      String raw = openThreadRaw(viewer, threadId);
      JsonNode msg = json.readTree(raw).get("messages").get(0);
      assertThat(addressesOf(msg.get("to"))).containsExactly("hr@anvicorp");
      assertThat(addressesOf(msg.get("cc"))).containsExactly("mgr@anvicorp");
      assertThat(addressesOf(msg.get("bcc"))).isEmpty();
      assertThat(raw).doesNotContain("acc@anvicorp").doesNotContain(accA.getId());
    }

    // The SENDER sees all BCC on their sent copy.
    JsonNode sentMsg = openThreadJson(caA, threadId).get("messages").get(0);
    assertThat(addressesOf(sentMsg.get("bcc"))).containsExactly("acc@anvicorp");

    // A BCC recipient sees To+Cc + THEMSELVES only (never other BCCs — there are none here, but they see
    // their own membership and nobody else's).
    JsonNode bccMsg = openThreadJson(accA, threadId).get("messages").get(0);
    assertThat(addressesOf(bccMsg.get("to"))).containsExactly("hr@anvicorp");
    assertThat(addressesOf(bccMsg.get("cc"))).containsExactly("mgr@anvicorp");
    assertThat(addressesOf(bccMsg.get("bcc"))).containsExactly("acc@anvicorp");
  }

  @Test
  void reply_goesToSenderOnly_replyAll_addsToAndCc_excludingActorAndBcc() throws Exception {
    String threadId =
        compose(caA, List.of(hrA.getId()), List.of(mgrA.getId()), List.of(accA.getId()), "Kickoff", "Hi.");

    reply(hrA, threadId, "Thanks.").andExpect(status().isOk()); // -> the sender (caA) only
    replyAll(hrA, threadId, "On it.").andExpect(status().isOk()); // -> caA + CC mgrA (not hrA, not BCC accA)

    // Each viewer sees exactly the messages they SENT or RECEIVED:
    assertThat(openThreadJson(caA, threadId).get("messages")).hasSize(3); // sent original + reply + reply-all
    assertThat(openThreadJson(hrA, threadId).get("messages")).hasSize(3); // original + sent reply + reply-all
    // mgrA (CC) got the original + the reply-all, but NOT the sender-only reply.
    assertThat(openThreadJson(mgrA, threadId).get("messages")).hasSize(2);
    // accA (BCC) got ONLY the original — reply and reply-all never reach a BCC.
    assertThat(openThreadJson(accA, threadId).get("messages")).hasSize(1);
  }

  @Test
  void replyAll_revalidatesEveryRecipient_403WhenOneBecomesCrossCompany() throws Exception {
    String threadId = compose(caA, List.of(hrA.getId()), List.of(mgrA.getId()), List.of(), "Hi", "Hello.");
    // mgrA moves to another company AFTER the thread started. Reply All would target caA + mgrA; the
    // graph re-check makes hrA<->mgrA (now testco) cross-company -> the whole reply-all is rejected.
    mgrA.setCompanyId(testco);
    users.saveAndFlush(mgrA);
    replyAll(hrA, threadId, "Hi all").andExpect(status().isForbidden());
  }

  @Test
  void perRecipientReadStateIsIndependentAcrossToAndCc() throws Exception {
    String threadId = compose(caA, List.of(hrA.getId()), List.of(mgrA.getId()), List.of(), "Sync", "Hi.");
    // hrA opening marks only hrA's own copy read; mgrA's CC copy stays unread.
    openThreadJson(hrA, threadId);
    assertThat(unread(hrA)).isZero();
    assertThat(unread(mgrA)).isEqualTo(1);
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

  // --- Starred (per-user, thread-level, §8) ---------------------------------

  @Test
  void starIsPerUser_threadLevel_listsInStarredView_andUnstarClearsIt() throws Exception {
    String threadId = startThread(caA, hrA, "Roadmap", "The Q3 plan is attached.");

    // hrA (a recipient) stars the whole conversation.
    star(hrA, threadId).andExpect(status().isNoContent());
    assertThat(audit("MAIL_STARRED")).isEqualTo(1);

    // It appears in hrA's Starred view AND the inbox row is flagged starred.
    assertThat(threadRow(starred(hrA), threadId)).isNotNull();
    assertThat(threadRow(inbox(hrA), threadId).get("starred").asBoolean()).isTrue();

    // PER-USER: caA (the sender, a participant of the SAME thread) does NOT see it starred, and their
    // own Starred view is empty — my stars are mine alone.
    assertThat(threadRow(sent(caA), threadId).get("starred").asBoolean()).isFalse();
    assertThat(starred(caA).get("totalElements").asInt()).isZero();

    // Starring NEVER moves or deletes: the thread still sits in hrA's inbox and stays UNREAD (independent
    // of read-state) — starred AND unread AND in Inbox simultaneously.
    assertThat(threadRow(inbox(hrA), threadId).get("unread").asBoolean()).isTrue();
    assertThat(unread(hrA)).isEqualTo(1);

    // Star is idempotent (a second star does not add a second audit / a duplicate row).
    star(hrA, threadId).andExpect(status().isNoContent());
    assertThat(audit("MAIL_STARRED")).isEqualTo(1);

    // Unstar removes it from Starred and clears the row flag; audited once.
    unstar(hrA, threadId).andExpect(status().isNoContent());
    assertThat(audit("MAIL_UNSTARRED")).isEqualTo(1);
    assertThat(starred(hrA).get("totalElements").asInt()).isZero();
    assertThat(threadRow(inbox(hrA), threadId).get("starred").asBoolean()).isFalse();

    // Unstar is idempotent too (clearing an already-clear star does not audit again).
    unstar(hrA, threadId).andExpect(status().isNoContent());
    assertThat(audit("MAIL_UNSTARRED")).isEqualTo(1);
  }

  @Test
  void aSenderCanStarASentOnlyThread_provingItIsThreadLevelNotPerMessage() throws Exception {
    // caA is ONLY a sender here (no recipient row of their own). Because a star is thread-level, they can
    // still star it — and only they see it starred.
    String threadId = startThread(caA, hrA, "FYI", "For your information.");
    star(caA, threadId).andExpect(status().isNoContent());

    assertThat(threadRow(starred(caA), threadId)).isNotNull();
    assertThat(threadRow(sent(caA), threadId).get("starred").asBoolean()).isTrue();
    // The recipient (hrA) does NOT see the sender's star.
    assertThat(threadRow(inbox(hrA), threadId).get("starred").asBoolean()).isFalse();
    assertThat(starred(hrA).get("totalElements").asInt()).isZero();

    // The OPEN-thread view also exposes the viewer's per-user star (true for caA, false for hrA).
    assertThat(openThreadJson(caA, threadId).get("starred").asBoolean()).isTrue();
    assertThat(openThreadJson(hrA, threadId).get("starred").asBoolean()).isFalse();
  }

  @Test
  void aNonParticipantCannotStar_andAMissingThreadIs404() throws Exception {
    String threadId = startThread(caA, hrA, "Private", "Between us.");
    // mgrA is not in this thread -> 403 (a participant-only action).
    star(mgrA, threadId).andExpect(status().isForbidden());
    // A thread that does not exist -> 404.
    star(caA, "does-not-exist").andExpect(status().isNotFound());
    assertThat(audit("MAIL_STARRED")).isZero();
  }

  @Test
  void starredViewRespectsTheViewersSoftDelete() throws Exception {
    String threadId = startThread(caA, hrA, "Note", "A note.");
    star(hrA, threadId).andExpect(status().isNoContent());
    assertThat(threadRow(starred(hrA), threadId)).isNotNull();

    // hrA soft-deletes their copy — the starred thread no longer surfaces (same soft-delete scoping as
    // Inbox). The star row itself is untouched; it just isn't listed while the copy is hidden.
    mvc.perform(delete("/mail/threads/" + threadId).header("Authorization", "Bearer " + token(hrA)))
        .andExpect(status().isNoContent());
    assertThat(starred(hrA).get("totalElements").asInt()).isZero();
  }

  // --- Attachments (Stage 4) ------------------------------------------------

  @Test
  void attachmentsUploadBindDownload_andAreParticipantScoped() throws Exception {
    String a1 = uploadDraft(caA, "photo.png", "image/png", 1024);
    String a2 = uploadDraft(caA, "doc.pdf", "application/pdf", 2048);

    String threadId = startThreadWith(caA, hrA, "Docs", "See attached.", List.of(a1, a2));

    // HR sees both attachments on the message + the paperclip on the list row.
    JsonNode atts = openThreadJson(hrA, threadId).get("messages").get(0).get("attachments");
    assertThat(atts).hasSize(2);
    assertThat(atts.get(0).get("fileName").asText()).isEqualTo("photo.png");
    assertThat(atts.get(0).get("sizeBytes").asLong()).isGreaterThan(0);
    assertThat(threadRow(inbox(hrA), threadId).get("hasAttachments").asBoolean()).isTrue();

    // A participant downloads one -> 200 + audited.
    download(hrA, a1).andExpect(status().isOk());
    assertThat(audit("MAIL_ATTACHMENT_DOWNLOADED")).isEqualTo(1);

    // A non-participant -> 403, INCLUDING a Super Admin who is not in the thread.
    download(mgrA, a1).andExpect(status().isForbidden());
    download(sa, a1).andExpect(status().isForbidden());
    assertThat(audit("MAIL_ATTACHMENT_DOWNLOADED")).isEqualTo(1); // the 403s did not audit a download
  }

  @Test
  void serverEnforcesTypeExtensionSizeAndCountLimits() throws Exception {
    // Executable -> content type not in the allowlist -> 415.
    uploadExpect(caA, "malware.exe", "application/octet-stream", 10, status().isUnsupportedMediaType());
    // A script spoofing a PDF content type -> the extension doesn't match -> 400 (extension IS validated).
    uploadExpect(caA, "run.sh", "application/pdf", 10, status().isBadRequest());
    // Over 10 MB -> 413 (the upload endpoint is the server-side gate; a client cannot bypass it).
    uploadExpect(caA, "big.pdf", "application/pdf", 11L * 1024 * 1024, status().isPayloadTooLarge());
    // More than 5 attachments on one message -> 400.
    mvc.perform(
            post("/mail/messages")
                .header("Authorization", "Bearer " + token(caA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "toUserIds", List.of(hrA.getId()), "subject", "s", "body", "b",
                            "attachmentIds", List.of("a", "b", "c", "d", "e", "f")))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bindRechecksTheActualStoredSize_notTheClientClaim() throws Exception {
    // The upload declares a small size (passing the upload gate)…
    String a = uploadDraft(caA, "big.pdf", "application/pdf", 10);
    // …but the stored object is actually > 10 MB. Bind reads the real bytes and rejects it.
    when(storage.getObjectBytes("mail/att/big.pdf")).thenReturn(new byte[(int) (11L * 1024 * 1024)]);
    mvc.perform(
            post("/mail/messages")
                .header("Authorization", "Bearer " + token(caA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("toUserIds", List.of(hrA.getId()), "subject", "s", "body", "b", "attachmentIds", List.of(a)))))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void replyCanCarryAnAttachment_downloadableByTheCounterparty() throws Exception {
    String threadId = startThread(caA, hrA, "Q", "Question?");
    String a = uploadDraft(hrA, "answer.pdf", "application/pdf", 512);
    replyWith(hrA, threadId, "Answer attached.", List.of(a)).andExpect(status().isOk());

    assertThat(openThreadJson(caA, threadId).get("messages").get(1).get("attachments")).hasSize(1);
    download(caA, a).andExpect(status().isOk()); // the counterparty downloads it
  }

  @Test
  void deletingAThreadKeepsTheAttachmentForTheOtherParticipant() throws Exception {
    String a = uploadDraft(caA, "photo.png", "image/png", 256);
    String threadId = startThreadWith(caA, hrA, "Pic", "Here.", List.of(a));
    // HR soft-deletes their copy...
    mvc.perform(delete("/mail/threads/" + threadId).header("Authorization", "Bearer " + token(hrA)))
        .andExpect(status().isNoContent());
    // ...the Company Admin (still a participant) can still download the attachment (it follows the message).
    download(caA, a).andExpect(status().isOk());
  }

  // --- Employee mailbox (§8, Stage 5) ---------------------------------------

  @Test
  void anEmployeeWithoutCredentialsIsRefusedTheMailbox() throws Exception {
    String tok = empToken(employee(anvi, hrA, null)); // approved but no mailbox yet
    mvc.perform(get("/mail/contacts").header("Authorization", "Bearer " + tok))
        .andExpect(status().isForbidden());
    mvc.perform(get("/mail/inbox").header("Authorization", "Bearer " + tok))
        .andExpect(status().isForbidden());
  }

  @Test
  void aCredentialedEmployeeMailsTheirTeamAndCompanyAdmin() throws Exception {
    String emp = empToken(empA1); // credentialed, Team A (onboarded by hrA)

    // Allowed: their HR, their Manager, a SAME-TEAM employee, their team Accountant (§8d), the Company Admin.
    sendExpect(emp, hrA.getId(), status().isOk());
    sendExpect(emp, mgrA.getId(), status().isOk());
    sendExpect(emp, empA2.getId(), status().isOk());
    sendExpect(emp, accA.getId(), status().isOk()); // §8d — the team Accountant edge
    sendExpect(emp, caA.getId(), status().isOk());

    // Denied: another team's HR/Manager/employee, platform admins (the team Accountant is now allowed).
    sendExpect(emp, hr2A.getId(), status().isForbidden());
    sendExpect(emp, mgr2A.getId(), status().isForbidden());
    sendExpect(emp, empB1.getId(), status().isForbidden());
    sendExpect(emp, sa.getId(), status().isForbidden());

    // A reply into the thread the employee started with their HR still works (recipient derived).
    String threadId =
        json.readTree(sendT(emp, hrA.getId(), "Question", "When do I start?")).get("threadId").asText();
    reply(hrA, threadId, "Next Monday.").andExpect(status().isOk());
    assertThat(getT(emp, "/mail/unread-count").get("unread").asInt()).isEqualTo(1);
  }

  @Test
  void twoEmployeesOnDifferentTeamsCannotMailButBothReachTheCompanyAdmin() throws Exception {
    // Same company, different onboarding HR => different teams => denied both ways.
    sendExpect(empToken(empA1), empB1.getId(), status().isForbidden());
    sendExpect(empToken(empB1), empA1.getId(), status().isForbidden());
    // ...but each can mail the shared Company Admin.
    sendExpect(empToken(empA1), caA.getId(), status().isOk());
    sendExpect(empToken(empB1), caA.getId(), status().isOk());
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

  private void team(String companyId, User hr, User manager, User accountant) {
    Team t = new Team();
    t.setName("Team " + hr.getMailLocalPart());
    t.setCompanyId(companyId);
    t.setHrUserId(hr.getId());
    t.setManagerUserId(manager.getId());
    if (accountant != null) {
      t.setAccountantUserId(accountant.getId());
    }
    teams.save(t);
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

  /** Seed an APPROVED employee onboarded by {@code hr}; a non-null {@code mailAddress} = credentialed. */
  private Employee employee(String companyId, User hr, String mailAddress) {
    Employee e = new Employee();
    e.setFullName("Emp " + (mailAddress == null ? "nomail" : mailAddress));
    e.setEmail("personal-" + java.util.UUID.randomUUID() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hr.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    if (mailAddress != null) {
      e.setMailLocalPart(mailAddress.substring(0, mailAddress.indexOf('@')));
      e.setMailAddress(mailAddress);
      e.setPasswordHash("hashed"); // login is tested elsewhere; mail only needs a mailbox
    }
    return employees.save(e);
  }

  private String empToken(Employee e) {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee(
            e.getId(), e.getEmployeeCode(), e.getEmail(), e.getCompanyId(), e.getFullName(), e.getMailAddress()));
  }

  /** GET a path with a raw bearer token (expects 200); returns the parsed body. */
  private JsonNode getT(String token, String path) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private Set<String> contactAddrs(String token) throws Exception {
    Set<String> out = new HashSet<>();
    getT(token, "/mail/contacts").forEach(n -> out.add(n.get("address").asText()));
    return out;
  }

  /** Send via a raw token (expects 200); returns the body. */
  private String sendT(String token, String toId, String subject, String body) throws Exception {
    return mvc.perform(
            post("/mail/messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("toUserIds", List.of(toId), "subject", subject, "body", body))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void sendExpect(
      String token, String toId, org.springframework.test.web.servlet.ResultMatcher expected)
      throws Exception {
    mvc.perform(
            post("/mail/messages")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("toUserIds", List.of(toId), "subject", "s", "body", "b"))))
        .andExpect(expected);
  }

  /** POST /mail/messages from -> to (no status assertion). */
  private ResultActions send(User from, User to) throws Exception {
    return mvc.perform(
        post("/mail/messages")
            .header("Authorization", "Bearer " + token(from))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("toUserIds", List.of(to.getId()), "subject", "s", "body", "b"))));
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
                            Map.of("toUserIds", List.of(to.getId()), "subject", subject, "body", body))))
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

  private ResultActions replyAll(User who, String threadId, String body) throws Exception {
    return mvc.perform(
        post("/mail/threads/" + threadId + "/reply-all")
            .header("Authorization", "Bearer " + token(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("body", body))));
  }

  /** Compose to TO+CC+BCC lists (expects 200); returns the threadId. */
  private String compose(
      User from, List<String> to, List<String> cc, List<String> bcc, String subject, String body)
      throws Exception {
    String out =
        composeRaw(token(from), to, cc, bcc, subject, body)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(out).get("threadId").asText();
  }

  private ResultActions composeRaw(String token, List<String> to, List<String> cc, List<String> bcc)
      throws Exception {
    return composeRaw(token, to, cc, bcc, "s", "b");
  }

  private ResultActions composeRaw(
      String token, List<String> to, List<String> cc, List<String> bcc, String subject, String body)
      throws Exception {
    return mvc.perform(
        post("/mail/messages")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of(
                        "toUserIds", to,
                        "ccUserIds", cc,
                        "bccUserIds", bcc,
                        "subject", subject,
                        "body", body))));
  }

  private String openThreadRaw(User who, String threadId) throws Exception {
    return mvc.perform(get("/mail/threads/" + threadId).header("Authorization", "Bearer " + token(who)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private Set<String> addressesOf(JsonNode partyArray) {
    Set<String> out = new HashSet<>();
    if (partyArray != null) {
      partyArray.forEach(p -> out.add(p.get("address").asText()));
    }
    return out;
  }

  private JsonNode replyOk(User who, String threadId, String body) throws Exception {
    return json.readTree(
        reply(who, threadId, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  /** Request a presigned upload (expects 200); returns the draft attachment id. */
  private String uploadDraft(User who, String fileName, String contentType, long sizeBytes)
      throws Exception {
    String out =
        mvc.perform(
                post("/mail/attachments/upload-url")
                    .header("Authorization", "Bearer " + token(who))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("fileName", fileName, "contentType", contentType, "sizeBytes", sizeBytes))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(out).get("attachmentId").asText();
  }

  private void uploadExpect(
      User who,
      String fileName,
      String contentType,
      long sizeBytes,
      org.springframework.test.web.servlet.ResultMatcher expected)
      throws Exception {
    mvc.perform(
            post("/mail/attachments/upload-url")
                .header("Authorization", "Bearer " + token(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("fileName", fileName, "contentType", contentType, "sizeBytes", sizeBytes))))
        .andExpect(expected);
  }

  private String startThreadWith(User from, User to, String subject, String body, List<String> attachmentIds)
      throws Exception {
    String out =
        mvc.perform(
                post("/mail/messages")
                    .header("Authorization", "Bearer " + token(from))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "toUserIds", List.of(to.getId()), "subject", subject, "body", body,
                                "attachmentIds", attachmentIds))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(out).get("threadId").asText();
  }

  private ResultActions replyWith(User who, String threadId, String body, List<String> attachmentIds)
      throws Exception {
    return mvc.perform(
        post("/mail/threads/" + threadId + "/reply")
            .header("Authorization", "Bearer " + token(who))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("body", body, "attachmentIds", attachmentIds))));
  }

  private ResultActions download(User who, String attachmentId) throws Exception {
    return mvc.perform(
        get("/mail/attachments/" + attachmentId + "/download")
            .header("Authorization", "Bearer " + token(who)));
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

  private JsonNode starred(User who) throws Exception {
    return getJson(who, "/mail/starred");
  }

  private ResultActions star(User who, String threadId) throws Exception {
    return mvc.perform(
        post("/mail/threads/" + threadId + "/star").header("Authorization", "Bearer " + token(who)));
  }

  private ResultActions unstar(User who, String threadId) throws Exception {
    return mvc.perform(
        delete("/mail/threads/" + threadId + "/star").header("Authorization", "Bearer " + token(who)));
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
