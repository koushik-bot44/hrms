package com.ihrms.offboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.AuthService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.OffboardingDocument;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRequestRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.util.List;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Offboarding stage 3 (§3.6): the letters (gate + routing + fulfil), HR completion (all-docs-verified →
 * case COMPLETED + employee OFFBOARDED + terminal), the OFFBOARDED login gate, and the reconciliation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OffboardingStage3Test {

  private static final byte[] FILE_BYTES = "letter".getBytes();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired AuthService auth;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired OffboardingCaseRepository cases;
  @Autowired OffboardingDocumentRepository docs;
  @Autowired DocumentRequestRepository requests;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hr1;
  private User manager1;
  private String hr1Token;
  private Employee emp;
  private String empToken;
  private OffboardingCase c;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"document_requests\",\"request_documents\","
            + "\"signatures\",\"generated_documents\",\"employee_agreements\",\"offboarding_documents\","
            + "\"offboarding_clearance\",\"offboarding_cases\",\"approval_requests\",\"notifications\","
            + "\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("Globex Corporation", "GLBX");
    hr1 = user(companyA, UserRole.HR, "asha.hr@globex.test", "Asha Rao");
    manager1 = user(companyA, UserRole.MANAGER, "mgr@globex.test", "Mgr One");
    team(companyA, hr1.getId(), manager1.getId());
    hr1Token = tokenFor(hr1, UserRole.HR);
    emp = approvedEmployee(hr1.getId(), "GLBX-EMP-000001");
    empToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(emp.getId(), emp.getEmployeeCode(), emp.getEmail(), companyA));
    c = offboardingCase(emp.getId(), hr1.getId());

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://s/get");
    when(storage.presignedPutUrl(anyString(), anyString(), anyInt())).thenReturn("http://s/put");
    when(storage.getObjectBytes(any())).thenReturn(FILE_BYTES);
    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(inv -> "companies/x/" + inv.getArgument(3));
  }

  // --- letters --------------------------------------------------------------

  @Test
  void lettersGatedRoutedToHrOneOpenPerType() throws Exception {
    // A sent-but-unverified document keeps the gate closed.
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.SUBMITTED);
    requestLetter("RELIEVING_LETTER").andExpect(status().isConflict());

    // Verify it -> gate opens.
    verifyDocRow(OffboardingDocType.EXIT_FORMALITIES);
    requestLetter("RELIEVING_LETTER").andExpect(status().isOk());
    // Routed to the case HR (not an accountant).
    assertThat(requests.findByEmployeeIdAndRequestTypeInOrderByCreatedAtDesc(emp.getId(), List.of(RequestType.RELIEVING_LETTER)))
        .singleElement()
        .satisfies(r -> assertThat(r.getAccountantUserId()).isEqualTo(hr1.getId()));
    assertThat(auditLogs.findByAction("REQUEST_SUBMITTED")).hasSize(1);

    // One open per type; the other letter is independent.
    requestLetter("RELIEVING_LETTER").andExpect(status().isConflict());
    requestLetter("EXPERIENCE_LETTER").andExpect(status().isOk());
  }

  @Test
  void letterFulfilByUploadResolvesAndExposesDownload() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    requestLetter("RELIEVING_LETTER").andExpect(status().isOk());

    // HR fulfils via the reused handshake: begin-upload -> resolve.
    JsonNode up =
        json.readTree(
            mvc.perform(post("/employees/" + emp.getId() + "/offboarding/letters/RELIEVING_LETTER/begin-upload")
                    .header("Authorization", "Bearer " + hr1Token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fileName", "relieving.pdf", "contentType", "application/pdf", "sizeBytes", FILE_BYTES.length))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String docId = up.get("documentId").asText();
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/letters/RELIEVING_LETTER/resolve")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("documentIds", List.of(docId)))))
        .andExpect(status().isOk());

    // Employee sees it RESOLVED with a download link.
    JsonNode mine =
        json.readTree(
            mvc.perform(get("/me/offboarding/letters").header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode relieving =
        java.util.stream.StreamSupport.stream(mine.get("letters").spliterator(), false)
            .filter(l -> l.get("type").asText().equals("RELIEVING_LETTER"))
            .findFirst()
            .orElseThrow();
    assertThat(relieving.get("status").asText()).isEqualTo("RESOLVED");
    assertThat(relieving.get("downloadUrl").asText()).startsWith("http");
  }

  // --- completion + terminal ------------------------------------------------

  @Test
  void completeRequiresAllVerifiedThenOffboardsAndIsTerminal() throws Exception {
    // No documents yet -> cannot complete.
    complete().andExpect(status().isConflict());
    // A sent-but-unverified doc -> still cannot complete.
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.SUBMITTED);
    complete().andExpect(status().isConflict());

    // Verify everything -> complete.
    verifyDocRow(OffboardingDocType.EXIT_FORMALITIES);
    complete().andExpect(status().isOk());
    assertThat(cases.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(OffboardingStatus.COMPLETED);
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus()).isEqualTo(EmployeeStatus.OFFBOARDED);
    assertThat(auditLogs.findByAction("OFFBOARDING_COMPLETED")).hasSize(1);

    // Terminal: complete again, cancel, initiate a fresh case, send docs — all 409.
    complete().andExpect(status().isConflict());
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/cancel").header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isConflict());
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/initiate")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", "again", "lastWorkingDay", "2026-12-31"))))
        .andExpect(status().isConflict());
  }

  // --- login gate -----------------------------------------------------------

  @Test
  void offboardedEmployeeCannotRefresh() {
    String refresh = tokens.issueRefresh(
        new IhrmsPrincipal.Employee(emp.getId(), emp.getEmployeeCode(), emp.getEmail(), companyA), "OTP");
    // Before offboarding: refresh works.
    assertThat(auth.refresh(refresh)).isNotNull();

    // Offboard and try again -> deactivated.
    emp.setStatus(EmployeeStatus.OFFBOARDED);
    employees.save(emp);
    assertThatThrownBy(() -> auth.refresh(refresh))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
  }

  // --- reconciliation -------------------------------------------------------

  @Test
  void offboardedDropsFromApprovedCountsAndFunnelCountsIt() {
    assertThat(employees.countByCompanyIdAndStatus(companyA, EmployeeStatus.APPROVED)).isEqualTo(1);
    emp.setStatus(EmployeeStatus.OFFBOARDED);
    employees.save(emp);
    // Approved count drops; the offboarded employee is counted under its own status (funnel input).
    assertThat(employees.countByCompanyIdAndStatus(companyA, EmployeeStatus.APPROVED)).isZero();
    assertThat(employees.countByCompanyIdAndStatus(companyA, EmployeeStatus.OFFBOARDED)).isEqualTo(1);
  }

  // --- helpers --------------------------------------------------------------

  private org.springframework.test.web.servlet.ResultActions requestLetter(String type) throws Exception {
    return mvc.perform(post("/me/offboarding/letters/" + type)
        .header("Authorization", "Bearer " + empToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"));
  }

  private org.springframework.test.web.servlet.ResultActions complete() throws Exception {
    return mvc.perform(post("/employees/" + emp.getId() + "/offboarding/complete")
        .header("Authorization", "Bearer " + hr1Token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"));
  }

  private void doc(OffboardingDocType type, OffboardingDocStatus status) {
    OffboardingDocument d = new OffboardingDocument();
    d.setCaseId(c.getId());
    d.setType(type);
    d.setStatus(status);
    d.setSentByUserId(hr1.getId());
    docs.save(d);
  }

  private void verifyDocRow(OffboardingDocType type) {
    OffboardingDocument d = docs.findByCaseIdAndType(c.getId(), type).orElseThrow();
    d.setStatus(OffboardingDocStatus.VERIFIED);
    docs.save(d);
  }

  private String company(String name, String code) {
    Company cc = new Company();
    cc.setName(name);
    cc.setCode(code);
    return companies.save(cc).getId();
  }

  private User user(String companyId, UserRole role, String email, String name) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
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

  private Employee approvedEmployee(String hrId, String code) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName("Meera Nair");
    e.setEmail("meera@personal.test");
    e.setDesignation("Engineer");
    e.setMailAddress("meera@globex.mail");
    e.setCompanyId(companyA);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private OffboardingCase offboardingCase(String employeeId, String hrId) {
    OffboardingCase oc = new OffboardingCase();
    oc.setEmployeeId(employeeId);
    oc.setStatus(OffboardingStatus.APPROVED);
    oc.setReason("Resignation");
    oc.setLastWorkingDay(java.time.LocalDate.parse("2026-09-30"));
    oc.setInitiatedByUserId(hrId);
    return cases.save(oc);
  }

  private String tokenFor(User u, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), role, u.getCompanyId(), null));
  }
}
