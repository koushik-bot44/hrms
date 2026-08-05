package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OfferStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.EmployeeOffer;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeOfferRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The Offer Letter that opens onboarding (§3.2): the invite requires the offer; the offer GATES the onboarding
 * forms (409 while SENT, unlocked on accept); a pre-feature employee with no offer row is ungated; accept
 * requires consent + signature and stores the PDF + audits; fidelity of the rendered text (clause 24 bold,
 * acceptance block, salary/joining/location, 2-company name matrix); and the salary-visibility matrix on the
 * offer PDF (HR/SA/self see it; manager/accountant 403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OfferApiTest {

  private static final String SIGNATURE =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired EmployeeOfferRepository offers;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hrA;
  private String hrAToken;
  private String superToken;
  private String managerToken;
  private String accountantToken;
  private Employee empA;
  private String empAToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"employee_offers\",\"signatures\","
            + "\"generated_documents\",\"employee_agreements\",\"approval_requests\",\"notifications\","
            + "\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");

    companyA = company("Globex Corporation", "GLBX");
    hrA = user(companyA, UserRole.HR, "asha.hr@globex.test");
    User mgr = user(companyA, UserRole.MANAGER, "mgr@globex.test");
    User acc = user(companyA, UserRole.ACCOUNTANT, "acc@globex.test");
    team(companyA, hrA.getId(), mgr.getId());
    hrAToken = tokenFor(hrA, UserRole.HR);
    managerToken = tokenFor(mgr, UserRole.MANAGER);
    accountantToken = tokenFor(acc, UserRole.ACCOUNTANT);
    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("super-1", "super@ihrms.test", "Super", UserRole.SUPER_ADMIN, null, null));

    empA = invitedEmployee(companyA, hrA.getId(), "Suresh Mucha", "suresh@personal.test");
    sentOffer(empA.getId(), "2023-12-11", "5,40,000 Per Annum", "Hyderabad");
    empAToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(empA.getId(), null, empA.getEmail(), companyA));

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://s/get");
    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(inv -> "companies/" + inv.getArgument(0) + "/" + inv.getArgument(3));
  }

  // --- invite requires the offer --------------------------------------------

  @Test
  void inviteRequiresOfferThenCreatesSentOffer() throws Exception {
    // No offer -> 400.
    mvc.perform(post("/employees")
            .header("Authorization", "Bearer " + hrAToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("form2", form2("Neo Hire", "neo@personal.test")))))
        .andExpect(status().isBadRequest());

    // With the offer -> 201, and a SENT offer row exists for the new employee.
    JsonNode res =
        json.readTree(
            mvc.perform(post("/employees")
                    .header("Authorization", "Bearer " + hrAToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of(
                        "form2", form2("Neo Hire", "neo@personal.test"),
                        "offer", Map.of("salary", "7,20,000 Per Annum")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    String newId = res.get("employee").get("id").asText();
    assertThat(offers.findByEmployeeId(newId))
        .get()
        .satisfies(o -> assertThat(o.getStatus()).isEqualTo(OfferStatus.SENT));
    assertThat(auditLogs.findByAction("OFFER_SENT")).isNotEmpty();
  }

  // --- the gate: forms locked while SENT, unlocked on accept ----------------

  @Test
  void offerGatesFormsThenUnlocksOnAccept() throws Exception {
    // While the offer is SENT, a form save is blocked.
    saveForm1(empAToken).andExpect(status().isConflict());

    // Accept -> 200; the offer becomes ACCEPTED.
    accept(empAToken, true, SIGNATURE).andExpect(status().isOk());
    assertThat(offers.findByEmployeeId(empA.getId()).orElseThrow().getStatus())
        .isEqualTo(OfferStatus.ACCEPTED);

    // Now the form saves.
    saveForm1(empAToken).andExpect(status().isOk());
  }

  @Test
  void employeeWithNoOfferRowIsUngated() throws Exception {
    // A pre-feature employee (no offer row) can save forms with no offer step.
    Employee legacy = invitedEmployee(companyA, hrA.getId(), "Old Timer", "old@personal.test");
    String token =
        tokens.issueAccess(new IhrmsPrincipal.Employee(legacy.getId(), null, legacy.getEmail(), companyA));
    saveForm1(token).andExpect(status().isOk());
  }

  // --- accept: consent + signature; stores the PDF + audits -----------------

  @Test
  void acceptRequiresConsentAndSignatureAndStoresPdf() throws Exception {
    accept(empAToken, false, SIGNATURE).andExpect(status().isBadRequest()); // no consent
    accept(empAToken, true, "").andExpect(status().isBadRequest()); // no signature

    accept(empAToken, true, SIGNATURE).andExpect(status().isOk());
    EmployeeOffer offer = offers.findByEmployeeId(empA.getId()).orElseThrow();
    assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    assertThat(offer.getAcceptedAt()).isNotNull();
    assertThat(offer.getStorageKey()).endsWith("OFFER_LETTER.pdf");
    assertThat(auditLogs.findByAction("OFFER_ACCEPTED")).hasSize(1);

    // Accepting again -> 409 (terminal).
    accept(empAToken, true, SIGNATURE).andExpect(status().isConflict());
  }

  // --- fidelity of the rendered offer text ----------------------------------

  @Test
  void offerTextRendersTermsWithClause24BoldAndAcceptanceBlock() throws Exception {
    String body = myOfferBody(empAToken);
    // The joining company + the typed terms.
    assertThat(body).contains("Globex Corporation").contains("Suresh Mucha").contains("5,40,000 Per Annum");
    assertThat(body).contains("posted at Hyderabad");
    // Joining date formatted into "Date of Appointment".
    assertThat(body).contains("You have joined us on 11 December 2023");
    // Clause 24 is present AND bold, with the inline company name substituted (no leftover "SCREATIVE").
    assertThat(body)
        .contains(
            "<b>24. Unless you complete the probation period as an employee of Globex Corporation,"
                + " reliving documents will not be released.</b>");
    assertThat(body).doesNotContain("SCREATIVE");
    // The acceptance block.
    assertThat(body).contains("I agree to accept the employment on the terms");

    // 2-company matrix: a second tenant renders ITS own name, not company A's.
    String companyB = company("Initech Ltd", "INTC");
    User hrB = user(companyB, UserRole.HR, "raj.hr@initech.test");
    Employee empB = invitedEmployee(companyB, hrB.getId(), "Sam Wilson", "sam@personal.test");
    sentOffer(empB.getId(), "2024-01-02", "9,00,000 Per Annum", "Bangalore");
    String empBToken =
        tokens.issueAccess(new IhrmsPrincipal.Employee(empB.getId(), null, empB.getEmail(), companyB));
    String bodyB = myOfferBody(empBToken);
    assertThat(bodyB).contains("Initech Ltd").contains("Bangalore").doesNotContain("Globex Corporation");
  }

  // --- salary-visibility matrix on the offer PDF ----------------------------

  @Test
  void offerPdfVisibleToHrSuperAndSelfButNotManagerOrAccountant() throws Exception {
    // Before acceptance there is no PDF -> 404.
    offerPdf(hrAToken).andExpect(status().isNotFound());

    accept(empAToken, true, SIGNATURE).andExpect(status().isOk());

    // HR + SUPER_ADMIN + self can reach the PDF.
    offerPdf(hrAToken).andExpect(status().isOk());
    offerPdf(superToken).andExpect(status().isOk());
    JsonNode mine = json.readTree(
        mvc.perform(get("/me/onboarding/offer").header("Authorization", "Bearer " + empAToken))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(mine.get("downloadUrl").asText()).startsWith("http");

    // Manager + Accountant are refused (the PDF carries the salary).
    offerPdf(managerToken).andExpect(status().isForbidden());
    offerPdf(accountantToken).andExpect(status().isForbidden());
  }

  // --- helpers --------------------------------------------------------------

  private ResultActions saveForm1(String token) throws Exception {
    return mvc.perform(put("/me/onboarding/form1")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"));
  }

  private ResultActions accept(String token, boolean consent, String signature) throws Exception {
    return mvc.perform(post("/me/onboarding/offer/accept")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("consentAccepted", consent, "signatureDataUrl", signature))));
  }

  private ResultActions offerPdf(String token) throws Exception {
    return mvc.perform(get("/employees/" + empA.getId() + "/offer/pdf")
        .header("Authorization", "Bearer " + token));
  }

  private String myOfferBody(String token) throws Exception {
    JsonNode res = json.readTree(
        mvc.perform(get("/me/onboarding/offer").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    return res.get("bodyHtml").asText();
  }

  private static Map<String, Object> form2(String fullName, String email) {
    return Map.of(
        "fullName", fullName,
        "personalEmail", email,
        "designation", "Talent Acquisition Specialist",
        "dateOfJoining", "2026-07-01");
  }

  private void sentOffer(String employeeId, String joiningDate, String salary, String location) {
    Map<String, Object> terms = new LinkedHashMap<>();
    terms.put("joiningDate", joiningDate);
    terms.put("salary", salary);
    terms.put("location", location);
    terms.put("offerDate", joiningDate);
    EmployeeOffer offer = new EmployeeOffer();
    offer.setEmployeeId(employeeId);
    offer.setTerms(terms);
    offer.setStatus(OfferStatus.SENT);
    offers.save(offer);
  }

  private String company(String name, String code) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name() + " " + email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private void team(String companyId, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    teams.save(t);
  }

  private Employee invitedEmployee(String companyId, String hrId, String fullName, String email) {
    Employee e = new Employee();
    e.setFullName(fullName);
    e.setEmail(email);
    e.setDesignation("Talent Acquisition Specialist");
    e.setDateOfJoining(LocalDate.parse("2023-12-11"));
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.INVITED);
    return employees.save(e);
  }

  private String tokenFor(User u, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), role, u.getCompanyId(), null));
  }
}
