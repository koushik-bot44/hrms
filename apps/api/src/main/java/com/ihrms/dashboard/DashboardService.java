package com.ihrms.dashboard;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.dashboard.dto.DashboardDtos.ActivityItem;
import com.ihrms.dashboard.dto.DashboardDtos.DashboardSummary;
import com.ihrms.dashboard.dto.DashboardDtos.EmployeeProgress;
import com.ihrms.dashboard.dto.DashboardDtos.StatCard;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Role dashboard summaries (§2/§7/§9). Every count is produced by an efficient scoped COUNT query
 * (never fetch-all-and-count) using the SAME scope the corresponding list view uses — so a count can
 * never reflect data outside the viewer's scope. The recent-activity feed is derived from the
 * viewer's scoped recent domain records (employees by updatedAt, the same scoped finders), which
 * keeps it scoped identically to the counts. Reads are not audited.
 */
@Service
public class DashboardService {

  private static final int FORMS_TOTAL = 4;

  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final ApprovalRequestRepository approvals;
  private final NotificationRepository notifications;
  private final CompanyRepository companies;
  private final UserRepository users;
  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;

  public DashboardService(
      EmployeeRepository employees,
      TeamRepository teams,
      ApprovalRequestRepository approvals,
      NotificationRepository notifications,
      CompanyRepository companies,
      UserRepository users,
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents) {
    this.employees = employees;
    this.teams = teams;
    this.approvals = approvals;
    this.notifications = notifications;
    this.companies = companies;
    this.users = users;
    this.form1s = form1s;
    this.form2s = form2s;
    this.form3s = form3s;
    this.documents = documents;
  }

  public DashboardSummary summary(IhrmsPrincipal principal) {
    if (principal instanceof IhrmsPrincipal.Employee e) {
      return employeeSummary(e);
    }
    IhrmsPrincipal.User u = (IhrmsPrincipal.User) principal;
    return switch (u.role()) {
      case SUPER_ADMIN -> superAdminSummary();
      case ACCOUNTS_ADMIN -> accountsAdminSummary();
      // HIERARCHY has its own aggregates area (§2, /hierarchy/**, added later) and does not use the
      // shared role dashboard — an empty summary keeps the shared endpoint safe if ever hit.
      case HIERARCHY -> new DashboardSummary("HIERARCHY", List.of(), List.of(), null);
      case COMPANY_ADMIN -> companyAdminSummary(u.companyId());
      case HR -> hrSummary(u.userId());
      case MANAGER -> managerSummary(u.userId());
      case ACCOUNTANT -> accountantTeamSummary(u.userId());
    };
  }

  // --- Accounts Admin (cross-company, read-only, approved employees) ---------

  private DashboardSummary accountsAdminSummary() {
    List<StatCard> stats =
        List.of(
            new StatCard("accountsAdmin.approvedEmployees", "Total inhouse employees",
                employees.countByStatus(EmployeeStatus.APPROVED)),
            new StatCard("accountsAdmin.companies", "Companies", companies.countByStatusNot("DELETED")));
    // Activity is the recently-approved employees across every company (flagged with their company).
    return new DashboardSummary(
        "ACCOUNTS_ADMIN", stats,
        activity(employees.findTop10ByStatusOrderByUpdatedAtDesc(EmployeeStatus.APPROVED), true), null);
  }

  // --- Accountant (team-scoped, read-only, approved employees of its team) ---

  private DashboardSummary accountantTeamSummary(String accountantUserId) {
    // The team's employees are those onboarded by its HR (like the Manager's scope, approved-only).
    List<String> hrIds =
        teams.findByAccountantUserId(accountantUserId).stream()
            .map(Team::getHrUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    long approved =
        hrIds.isEmpty()
            ? 0
            : employees.countByOnboardingHrIdInAndStatus(hrIds, EmployeeStatus.APPROVED);
    List<Employee> recent =
        hrIds.isEmpty()
            ? List.of()
            : employees.findTop10ByOnboardingHrIdInOrderByUpdatedAtDesc(hrIds).stream()
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
                .toList();
    List<StatCard> stats =
        List.of(new StatCard("accountant.approvedEmployees", "Total inhouse employees", approved));
    return new DashboardSummary("ACCOUNTANT", stats, activity(recent, false), null);
  }

  // --- Super Admin (org-wide) -----------------------------------------------

  private DashboardSummary superAdminSummary() {
    List<StatCard> stats =
        List.of(
            new StatCard("super.companiesActive", "Active companies", companies.countByStatusNot("DELETED")),
            new StatCard("super.companiesArchived", "Archived companies", companies.countByStatus("DELETED")),
            new StatCard("super.employeesTotal", "Employees", employees.count()),
            // Approval authority now sits with HR; "pending approval" = employees verified + awaiting an HR
            // decision (HR_VERIFIED), platform-wide (no manager approval-requests are pending anymore).
            new StatCard("super.pendingApprovals", "Pending approval", employees.countByStatus(EmployeeStatus.HR_VERIFIED)));
    return new DashboardSummary(
        "SUPER_ADMIN", stats, activity(employees.findTop10ByOrderByUpdatedAtDesc(), true), null);
  }

  // --- Company Admin (own company) ------------------------------------------

  private DashboardSummary companyAdminSummary(String companyId) {
    if (companyId == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No company in scope");
    }
    List<StatCard> stats =
        List.of(
            new StatCard("ca.teams", "Teams", teams.countByCompanyId(companyId)),
            new StatCard("ca.employees", "Employees", employees.countByCompanyId(companyId)),
            new StatCard("ca.pendingVerification", "Pending verification",
                employees.countByCompanyIdAndStatus(companyId, EmployeeStatus.SUBMITTED)),
            new StatCard("ca.pendingApprovals", "Pending approvals",
                employees.countByCompanyIdAndStatus(companyId, EmployeeStatus.HR_VERIFIED)),
            new StatCard("ca.approved", "Approved",
                employees.countByCompanyIdAndStatus(companyId, EmployeeStatus.APPROVED)));
    return new DashboardSummary(
        "COMPANY_ADMIN", stats,
        activity(employees.findTop10ByCompanyIdOrderByUpdatedAtDesc(companyId), false), null);
  }

  // --- HR (own onboarded employees) -----------------------------------------

  private DashboardSummary hrSummary(String hrId) {
    // Each card counts a SINGLE status so its value always equals its drill-down (?status=…) list.
    List<StatCard> stats =
        List.of(
            new StatCard("hr.onboarded", "Onboarded", employees.countByOnboardingHrId(hrId)),
            new StatCard("hr.inProgress", "In progress",
                employees.countByOnboardingHrIdAndStatus(hrId, EmployeeStatus.IN_PROGRESS)),
            new StatCard("hr.pendingVerification", "Pending verification",
                employees.countByOnboardingHrIdAndStatus(hrId, EmployeeStatus.SUBMITTED)),
            new StatCard("hr.inRevision", "In revision",
                employees.countByOnboardingHrIdAndStatus(hrId, EmployeeStatus.REVISION_REQUESTED)),
            new StatCard("hr.pendingApproval", "Pending approval",
                employees.countByOnboardingHrIdAndStatus(hrId, EmployeeStatus.HR_VERIFIED)),
            new StatCard("hr.approved", "Approved",
                employees.countByOnboardingHrIdAndStatus(hrId, EmployeeStatus.APPROVED)),
            new StatCard("hr.rejected", "Rejected",
                employees.countByOnboardingHrIdAndStatus(hrId, EmployeeStatus.REJECTED)));
    return new DashboardSummary(
        "HR", stats, activity(employees.findTop10ByOnboardingHrIdOrderByUpdatedAtDesc(hrId), false), null);
  }

  // --- Manager (own team scope) ---------------------------------------------

  private DashboardSummary managerSummary(String managerId) {
    List<String> hrIds =
        teams.findByManagerUserId(managerId).stream()
            .map(Team::getHrUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    // The onboarding queue = the team's employees still going through onboarding (approved ones have
    // left the pipeline, so they're excluded).
    long onboardingQueue =
        hrIds.isEmpty()
            ? 0
            : employees.countByOnboardingHrIdInAndStatusNot(hrIds, EmployeeStatus.APPROVED);
    List<Employee> recent =
        hrIds.isEmpty() ? List.of() : employees.findTop10ByOnboardingHrIdInOrderByUpdatedAtDesc(hrIds);

    // Approval authority now sits with HR (§3.3); the Manager has no pending-approval inbox. The "Approved"
    // card counts the employees approved onto this Manager's team (the read-only Team-onboarding history).
    List<StatCard> stats =
        List.of(
            new StatCard("manager.approved", "Approved onto team",
                approvals.countByManagerUserIdAndStatus(managerId, ApprovalStatus.APPROVED)),
            new StatCard("manager.onboardingQueue", "Onboarding queue", onboardingQueue),
            new StatCard("manager.unreadNotifications", "Unread notifications",
                notifications.countByRecipientUserIdAndReadFalse(managerId)));
    return new DashboardSummary("MANAGER", stats, activity(recent, false), null);
  }

  // --- Employee (own record) ------------------------------------------------

  private DashboardSummary employeeSummary(IhrmsPrincipal.Employee principal) {
    Employee employee =
        employees
            .findById(principal.employeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    String id = employee.getId();

    int completed = 0;
    if (form1s.findByEmployeeId(id).isPresent()) completed++;
    if (form2s.findByEmployeeId(id).isPresent()) completed++;
    if (!form3s.findByEmployeeIdOrderByOrderIndexAsc(id).isEmpty()) completed++;
    if (!documents.findByEmployeeId(id).isEmpty()) completed++;

    EmployeeStatus status = employee.getStatus();
    EmployeeProgress progress =
        new EmployeeProgress(
            completed, FORMS_TOTAL, status.name(), employee.getEmployeeCode(), nextAction(employee));

    ActivityItem current =
        new ActivityItem(
            employee.getUpdatedAt() == null ? null : employee.getUpdatedAt().toString(),
            null,
            statusPhrase(status),
            employee.getFullName() != null ? employee.getFullName() : employee.getEmail(),
            "EMPLOYEE",
            null);
    return new DashboardSummary("EMPLOYEE", List.of(), List.of(current), progress);
  }

  private static String nextAction(Employee e) {
    return switch (e.getStatus()) {
      case INVITED, IN_PROGRESS -> "Complete your onboarding forms, then sign and submit.";
      case REVISION_REQUESTED -> "HR asked for changes — update the flagged items and re-submit.";
      case REJECTED -> "Your submission needs changes — update your forms and re-submit.";
      case SUBMITTED -> "Submitted — awaiting HR verification.";
      case HR_VERIFIED -> "Verified — awaiting approval.";
      case APPROVED ->
          e.getEmployeeCode() == null
              ? "Approved — welcome aboard!"
              : "Approved — your employee ID is " + e.getEmployeeCode() + ".";
    };
  }

  // --- shared: scoped activity from recent employee records -----------------

  private List<ActivityItem> activity(List<Employee> emps, boolean withCompany) {
    Set<String> hrIds =
        emps.stream().map(Employee::getOnboardingHrId).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<String, String> hrNames =
        hrIds.isEmpty()
            ? Map.of()
            : users.findAllById(hrIds).stream().collect(Collectors.toMap(User::getId, User::getName));
    Map<String, String> companyNames = Map.of();
    if (withCompany) {
      Set<String> cids =
          emps.stream().map(Employee::getCompanyId).filter(Objects::nonNull).collect(Collectors.toSet());
      companyNames =
          cids.isEmpty()
              ? Map.of()
              : companies.findAllById(cids).stream()
                  .collect(Collectors.toMap(Company::getId, Company::getName));
    }
    Map<String, String> cn = companyNames;
    return emps.stream()
        .map(
            e ->
                new ActivityItem(
                    e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString(),
                    e.getOnboardingHrId() == null ? null : hrNames.get(e.getOnboardingHrId()),
                    statusPhrase(e.getStatus()),
                    e.getFullName() != null ? e.getFullName() : e.getEmail(),
                    "EMPLOYEE",
                    withCompany ? cn.get(e.getCompanyId()) : null))
        .toList();
  }

  private static String statusPhrase(EmployeeStatus status) {
    return switch (status) {
      case INVITED -> "Invited to onboard";
      case IN_PROGRESS -> "Onboarding in progress";
      case SUBMITTED -> "Submitted for verification";
      case REVISION_REQUESTED -> "Revision requested";
      case HR_VERIFIED -> "Verified — awaiting approval";
      case APPROVED -> "Approved";
      case REJECTED -> "Rejected";
    };
  }
}
