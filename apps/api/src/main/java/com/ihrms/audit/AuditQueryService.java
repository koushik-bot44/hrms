package com.ihrms.audit;

import com.ihrms.audit.dto.AuditDtos.AuditLogView;
import com.ihrms.audit.dto.AuditDtos.AuditPage;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read side of the per-company audit explorer (§7). Company scope is resolved via the centralized
 * {@link AuthorizationService}: SUPER_ADMIN may read ANY company's trail (one company per query, so
 * trails stay separated); COMPANY_ADMIN is locked to their own companyId. Every query filters by
 * companyId — no cross-company reads. AuditLog stays read-only (no update/delete path).
 */
@Service
public class AuditQueryService {

  /**
   * The approval-lifecycle audit actions the Accountant may read across companies (§7). Includes the current
   * HR-decision events ({@code HR_APPROVED}/{@code HR_REJECTED}) AND the legacy manager-era events
   * ({@code APPROVAL_ROUTED}/{@code APPROVAL_APPROVED}/{@code APPROVAL_REJECTED}) — the audit is append-only,
   * so the historical trail stays readable.
   */
  private static final Set<String> APPROVAL_ACTIONS =
      Set.of(
          "HR_APPROVED",
          "HR_REJECTED",
          "APPROVAL_ROUTED",
          "APPROVAL_APPROVED",
          "APPROVAL_REJECTED");

  private final AuditLogRepository auditLogs;
  private final UserRepository users;
  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final AuthorizationService authz;

  public AuditQueryService(
      AuditLogRepository auditLogs,
      UserRepository users,
      EmployeeRepository employees,
      CompanyRepository companies,
      AuthorizationService authz) {
    this.auditLogs = auditLogs;
    this.users = users;
    this.employees = employees;
    this.companies = companies;
    this.authz = authz;
  }

  public AuditPage query(
      IhrmsPrincipal.User actor,
      String companyIdParam,
      String action,
      String actorType,
      Instant from,
      Instant to,
      Pageable pageable) {
    String companyId = resolveCompany(actor, companyIdParam);

    Specification<AuditLog> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("companyId"), companyId)); // every query is companyId-scoped
          if (isPresent(action)) {
            p.add(cb.like(cb.lower(root.get("action")), "%" + action.toLowerCase() + "%"));
          }
          if (isPresent(actorType)) {
            p.add(cb.equal(root.get("actorType"), actorType));
          }
          if (from != null) {
            p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
          }
          if (to != null) {
            p.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
          }
          return cb.and(p.toArray(new Predicate[0]));
        };

    Page<AuditLog> page = auditLogs.findAll(spec, pageable);
    return new AuditPage(
        mapWithActors(page.getContent()),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        authz.isCompanyDeleted(companyId)); // still viewable, flagged as archived (§7)
  }

  /**
   * The APPROVAL-only audit view (§7): approval-lifecycle events, never the full trail. The
   * Accounts Admin reads it across all companies ({@code targetEmployeeIds == null}); the team
   * Accountant passes its team's employee ids so it sees only their approval events. Access is gated at
   * the controller; {@code companyDeleted} is not meaningful here, so it is false. An empty (non-null)
   * {@code targetEmployeeIds} means "no employees in scope" → an empty page.
   */
  public AuditPage approvalTrail(
      String companyIdParam,
      List<String> targetEmployeeIds,
      Instant from,
      Instant to,
      Pageable pageable) {
    if (targetEmployeeIds != null && targetEmployeeIds.isEmpty()) {
      return new AuditPage(List.of(), 0, pageable.getPageSize(), 0, 0, false);
    }
    Specification<AuditLog> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(root.get("action").in(APPROVAL_ACTIONS)); // approval events only (§7)
          if (targetEmployeeIds != null) {
            p.add(root.get("targetId").in(targetEmployeeIds)); // team scope
          }
          if (isPresent(companyIdParam)) {
            p.add(cb.equal(root.get("companyId"), companyIdParam));
          }
          if (from != null) {
            p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
          }
          if (to != null) {
            p.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
          }
          return cb.and(p.toArray(new Predicate[0]));
        };
    Page<AuditLog> page = auditLogs.findAll(spec, pageable);
    return new AuditPage(
        mapWithActors(page.getContent()),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        false);
  }

  /** SUPER_ADMIN picks any company (required); COMPANY_ADMIN is forced to their own (cross -> 403). */
  private String resolveCompany(IhrmsPrincipal.User actor, String companyIdParam) {
    if (actor.role() == UserRole.SUPER_ADMIN) {
      if (!isPresent(companyIdParam)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "companyId is required to view a company's audit trail");
      }
      return companyIdParam;
    }
    if (isPresent(companyIdParam)) {
      authz.assertCompany(actor, companyIdParam); // 403 if not the admin's own company
    }
    return actor.companyId();
  }

  private List<AuditLogView> mapWithActors(List<AuditLog> logs) {
    Set<String> userIds = actorIds(logs, "USER");
    Set<String> employeeIds = actorIds(logs, "EMPLOYEE");
    // Approval events target an Employee — resolve their names; and resolve every row's company name.
    Set<String> targetEmployeeIds =
        logs.stream()
            .filter(l -> "Employee".equals(l.getTargetType()) && l.getTargetId() != null)
            .map(AuditLog::getTargetId)
            .collect(Collectors.toSet());
    Set<String> companyIds =
        logs.stream().map(AuditLog::getCompanyId).filter(id -> id != null).collect(Collectors.toSet());

    Map<String, String> userNames =
        userIds.isEmpty()
            ? Map.of()
            : users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getName));
    // Employee actors may have no code yet (allocated on approval) — fall back to name/id, never null.
    Map<String, String> employeeCodes =
        employeeIds.isEmpty()
            ? Map.of()
            : employees.findAllById(employeeIds).stream()
                .collect(Collectors.toMap(Employee::getId, AuditQueryService::employeeLabel));
    Map<String, String> targetNames =
        targetEmployeeIds.isEmpty()
            ? Map.of()
            : employees.findAllById(targetEmployeeIds).stream()
                .collect(Collectors.toMap(Employee::getId, AuditQueryService::employeeName));
    Map<String, String> companyNames =
        companyIds.isEmpty()
            ? Map.of()
            : companies.findAllById(companyIds).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));

    return logs.stream()
        .map(l -> toView(l, userNames, employeeCodes, targetNames, companyNames))
        .toList();
  }

  private static Set<String> actorIds(List<AuditLog> logs, String actorType) {
    return logs.stream()
        .filter(l -> actorType.equals(l.getActorType()) && l.getActorId() != null)
        .map(AuditLog::getActorId)
        .collect(Collectors.toSet());
  }

  private static AuditLogView toView(
      AuditLog l,
      Map<String, String> userNames,
      Map<String, String> employeeCodes,
      Map<String, String> targetNames,
      Map<String, String> companyNames) {
    String label;
    if ("SYSTEM".equals(l.getActorType()) || l.getActorId() == null) {
      label = "System";
    } else if ("EMPLOYEE".equals(l.getActorType())) {
      label = employeeCodes.getOrDefault(l.getActorId(), l.getActorId());
    } else {
      label = userNames.getOrDefault(l.getActorId(), l.getActorId());
    }
    String targetLabel =
        "Employee".equals(l.getTargetType()) && l.getTargetId() != null
            ? targetNames.get(l.getTargetId())
            : null;
    return new AuditLogView(
        l.getId(),
        l.getCompanyId(),
        l.getCompanyId() == null ? null : companyNames.get(l.getCompanyId()),
        l.getActorType(),
        l.getActorId(),
        label,
        l.getAction(),
        l.getTargetType(),
        l.getTargetId(),
        targetLabel,
        l.getMetadata(),
        l.getIpAddress(),
        l.getCreatedAt().toString());
  }

  /** Label for an employee actor: code if approved, else full name, else the id — never null. */
  private static String employeeLabel(Employee e) {
    if (e.getEmployeeCode() != null) {
      return e.getEmployeeCode();
    }
    return e.getFullName() != null ? e.getFullName() : e.getId();
  }

  /** Label for a TARGET employee: prefer the human name, then the code, then the id — never null. */
  private static String employeeName(Employee e) {
    if (e.getFullName() != null && !e.getFullName().isBlank()) {
      return e.getFullName();
    }
    return e.getEmployeeCode() != null ? e.getEmployeeCode() : e.getId();
  }

  private static boolean isPresent(String s) {
    return s != null && !s.isBlank();
  }
}
