package com.ihrms.offboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ClearanceFinalStatus;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.OffboardingClearance;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingClearanceRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.offboarding.ClearanceSpec.ItemKind;
import com.ihrms.offboarding.ClearanceSpec.Section;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceDetails;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceItemView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceSectionView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceSummary;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceUpdateItem;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceUpdateRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.ClearanceView;
import com.ihrms.onboarding.FormMappers;
import com.ihrms.companies.LetterheadService;
import com.ihrms.storage.StorageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The HR-side offboarding clearance checklist (§3.6 stage 2). One per case. HR (the initiate scope) fills it;
 * a save upserts the row and regenerates the clearance PDF. The employee NEVER sees it (no employee endpoint).
 */
@Service
public class OffboardingClearanceService {

  private static final int DOWNLOAD_TTL_SECONDS = 300;

  private final EmployeeRepository employees;
  private final OffboardingCaseRepository cases;
  private final OffboardingClearanceRepository clearances;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final UserRepository users;
  private final Form2InfoRepository form2s;
  private final StorageService storage;
  private final LetterheadService letterheads;
  private final AuditService audit;

  public OffboardingClearanceService(
      EmployeeRepository employees,
      OffboardingCaseRepository cases,
      OffboardingClearanceRepository clearances,
      CompanyRepository companies,
      TeamRepository teams,
      UserRepository users,
      Form2InfoRepository form2s,
      StorageService storage,
      LetterheadService letterheads,
      AuditService audit) {
    this.employees = employees;
    this.cases = cases;
    this.clearances = clearances;
    this.companies = companies;
    this.teams = teams;
    this.users = users;
    this.form2s = form2s;
    this.storage = storage;
    this.letterheads = letterheads;
    this.audit = audit;
  }

  /** The clearance form for the HR record (HR only, case-scoped). Empty values until first saved. */
  @Transactional(readOnly = true)
  public ClearanceView get(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee = loadOwn(actor, employeeId);
    OffboardingCase c = caseFor(employeeId);
    OffboardingClearance existing = clearances.findByCaseId(c.getId()).orElse(null);
    return view(employee, c, existing);
  }

  /** HR upsert of the clearance + regenerate the PDF. Case must be APPROVED or later (i.e. exists). */
  @Transactional
  public ClearanceView save(
      IhrmsPrincipal.User actor, String employeeId, ClearanceUpdateRequest req, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    OffboardingCase c = caseFor(employeeId);
    OffboardingClearance cl = clearances.findByCaseId(c.getId()).orElseGet(OffboardingClearance::new);
    cl.setCaseId(c.getId());
    cl.setFilledByUserId(actor.userId());
    cl.setFinalStatus(req == null || req.finalStatus() == null ? ClearanceFinalStatus.PENDING : req.finalStatus());
    cl.setItems(normalizeItems(req));
    clearances.save(cl);

    // Regenerate the PDF from the spec + current values (best-effort within the tx — the storage.putObject
    // is the same server-side path the agreements/docs use).
    byte[] pdf = letterheads.renderBranded("offboarding-clearance", pdfModel(employee, c, cl), employee.getCompanyId());
    String key = storage.buildKey(employee.getCompanyId(), employee.getId(), "offboarding", "clearance.pdf");
    storage.putObject(key, pdf, "application/pdf");
    cl.setStorageKey(key);
    clearances.save(cl);

    audit.record(
        AuditActor.from(actor),
        "OFFBOARDING_CLEARANCE_SAVED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("caseId", c.getId(), "finalStatus", cl.getFinalStatus().name()),
        ip);
    return view(employee, c, cl);
  }

  /** A small summary for the record panel (null if not yet started). */
  @Transactional(readOnly = true)
  public ClearanceSummary summary(String caseId) {
    return clearances
        .findByCaseId(caseId)
        .map(cl -> new ClearanceSummary(cl.getFinalStatus(), downloadUrl(cl)))
        .orElse(null);
  }

  // --- internals ------------------------------------------------------------

  private ClearanceView view(Employee employee, OffboardingCase c, OffboardingClearance existing) {
    Map<String, Object> items = existing == null || existing.getItems() == null ? Map.of() : existing.getItems();
    List<ClearanceSectionView> sections = new ArrayList<>();
    for (Section s : ClearanceSpec.SECTIONS) {
      List<ClearanceItemView> itemViews = new ArrayList<>();
      for (ClearanceSpec.Item it : s.items()) {
        itemViews.add(
            new ClearanceItemView(it.key(), it.label(), itemValue(items, it.key()), itemRemarks(items, it.key())));
      }
      sections.add(new ClearanceSectionView(s.key(), s.title(), s.kind().name(), itemViews));
    }
    return new ClearanceView(
        details(employee, c),
        sections,
        itemValue(items, ClearanceSpec.FINAL_IT_SIGNOFF_KEY),
        existing == null ? ClearanceFinalStatus.PENDING : existing.getFinalStatus(),
        existing == null ? null : downloadUrl(existing));
  }

  private ClearanceDetails details(Employee employee, OffboardingCase c) {
    Team team =
        teams.findByCompanyIdAndHrUserId(employee.getCompanyId(), employee.getOnboardingHrId()).orElse(null);
    String manager =
        team == null || team.getManagerUserId() == null
            ? null
            : users.findById(team.getManagerUserId()).map(User::getName).orElse(null);
    String designation =
        form2s
            .findByEmployeeId(employee.getId())
            .map(f -> FormMappers.form2View(f, employee.getEmployeeCode()).designation())
            .orElse(employee.getDesignation());
    return new ClearanceDetails(
        employee.getFullName(),
        employee.getEmployeeCode(),
        team == null ? null : team.getName(),
        designation,
        manager,
        c.getLastWorkingDay() == null ? null : c.getLastWorkingDay().toString());
  }

  /** Keep only spec keys; each value is {value: YES|NO, remarks}. */
  private Map<String, Object> normalizeItems(ClearanceUpdateRequest req) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (req == null || req.items() == null) {
      return out;
    }
    for (Map.Entry<String, ClearanceUpdateItem> e : req.items().entrySet()) {
      if (!ClearanceSpec.KEYS.contains(e.getKey()) || e.getValue() == null) {
        continue; // ignore unknown keys
      }
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("value", e.getValue().value());
      v.put("remarks", e.getValue().remarks());
      out.put(e.getKey(), v);
    }
    // The final IT sign-off is a single yes/no carried on the request.
    if (req.finalItSignoff() != null && !req.finalItSignoff().isBlank()) {
      Map<String, Object> v = new LinkedHashMap<>();
      v.put("value", req.finalItSignoff());
      out.put(ClearanceSpec.FINAL_IT_SIGNOFF_KEY, v);
    }
    return out;
  }

  private Map<String, Object> pdfModel(Employee employee, OffboardingCase c, OffboardingClearance cl) {
    Map<String, Object> items = cl.getItems() == null ? Map.of() : cl.getItems();
    ClearanceDetails d = details(employee, c);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("companyName", companies.findById(employee.getCompanyId()).map(Company::getName).orElse("Company"));
    m.put("companyAddress", "");
    m.put("footerText", "Private & Confidential");
    m.put("d_name", nn(d.name()));
    m.put("d_employeeId", nn(d.employeeId()));
    m.put("d_department", nn(d.department()));
    m.put("d_designation", nn(d.designation()));
    m.put("d_manager", nn(d.manager()));
    m.put("d_lastWorkingDay", nn(d.lastWorkingDay()));

    List<Map<String, Object>> sections = new ArrayList<>();
    for (Section s : ClearanceSpec.SECTIONS) {
      boolean returned = s.kind() == ItemKind.RETURNED;
      List<Map<String, Object>> rows = new ArrayList<>();
      for (ClearanceSpec.Item it : s.items()) {
        String value = itemValue(items, it.key());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", it.label());
        row.put("yes", "YES".equals(value));
        row.put("no", "NO".equals(value));
        row.put("remarks", nn(itemRemarks(items, it.key())));
        rows.add(row);
      }
      Map<String, Object> sec = new LinkedHashMap<>();
      sec.put("title", s.title());
      sec.put("returned", returned);
      sec.put("rows", rows);
      sections.add(sec);
    }
    m.put("sections", sections);
    String signoff = itemValue(items, ClearanceSpec.FINAL_IT_SIGNOFF_KEY);
    m.put("finalSignoffYes", "YES".equals(signoff));
    m.put("finalSignoffNo", "NO".equals(signoff));
    m.put("statusApproved", cl.getFinalStatus() == ClearanceFinalStatus.APPROVED);
    m.put("statusPending", cl.getFinalStatus() == ClearanceFinalStatus.PENDING);
    m.put("statusOnHold", cl.getFinalStatus() == ClearanceFinalStatus.ON_HOLD);
    return m;
  }

  @SuppressWarnings("unchecked")
  private static String itemValue(Map<String, Object> items, String key) {
    Object o = items.get(key);
    if (o instanceof Map<?, ?> m) {
      Object v = ((Map<String, Object>) m).get("value");
      return v == null ? null : String.valueOf(v);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static String itemRemarks(Map<String, Object> items, String key) {
    Object o = items.get(key);
    if (o instanceof Map<?, ?> m) {
      Object v = ((Map<String, Object>) m).get("remarks");
      return v == null ? null : String.valueOf(v);
    }
    return null;
  }

  private String downloadUrl(OffboardingClearance cl) {
    return cl.getStorageKey() == null ? null : storage.presignedGetUrl(cl.getStorageKey(), DOWNLOAD_TTL_SECONDS);
  }

  private OffboardingCase caseFor(String employeeId) {
    return cases
        .findFirstByEmployeeIdOrderByInitiatedAtDesc(employeeId)
        .filter(c -> c.getStatus() == OffboardingStatus.APPROVED)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No approved offboarding case"));
  }

  private Employee loadOwn(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    boolean own =
        employee.getCompanyId().equals(actor.companyId())
            && employee.getOnboardingHrId().equals(actor.userId());
    if (!own) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
    }
    return employee;
  }

  private static String nn(String s) {
    return s == null ? "" : s;
  }
}
