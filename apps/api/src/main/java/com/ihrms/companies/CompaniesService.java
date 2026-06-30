package com.ihrms.companies;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.companies.dto.CompanyDtos.CompanyAdminView;
import com.ihrms.companies.dto.CompanyDtos.CompanyDetailView;
import com.ihrms.companies.dto.CompanyDtos.CompanySummaryView;
import com.ihrms.companies.dto.CompanyDtos.CreateCompanyRequest;
import com.ihrms.companies.dto.CompanyDtos.ProvisionAdminRequest;
import com.ihrms.companies.dto.CompanyDtos.ProvisionAdminResult;
import com.ihrms.companies.dto.CompanyDtos.UpdateCompanyRequest;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.EmployeeCodes;
import com.ihrms.support.TempPasswords;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Company management (ARCHITECTURE.md §2/§3.1). SUPER_ADMIN-only (enforced by the URL rule +
 * the controller's @PreAuthorize). Every mutation writes an explicit AuditLog row partitioned
 * under the TARGET company's companyId — the SUPER_ADMIN actor has no company of their own.
 */
@Service
public class CompaniesService {

  private final CompanyRepository companies;
  private final UserRepository users;
  private final TeamRepository teams;
  private final EmployeeRepository employees;
  private final AuditService audit;
  private final MailService mail;
  private final PasswordEncoder encoder;
  private final Environment env;

  public CompaniesService(
      CompanyRepository companies,
      UserRepository users,
      TeamRepository teams,
      EmployeeRepository employees,
      AuditService audit,
      MailService mail,
      PasswordEncoder encoder,
      Environment env) {
    this.companies = companies;
    this.users = users;
    this.teams = teams;
    this.employees = employees;
    this.audit = audit;
    this.mail = mail;
    this.encoder = encoder;
    this.env = env;
  }

  public CompanyDetailView create(CreateCompanyRequest input, IhrmsPrincipal.User actor, String ip) {
    String code = normalizeCode(input.code());
    Company company = new Company();
    company.setName(input.name().trim());
    company.setCode(code);
    try {
      companies.save(company);
    } catch (DataIntegrityViolationException e) {
      throw conflict("Company code \"" + code + "\" is already in use");
    }
    audit(actor, company.getId(), "COMPANY_CREATED", "Company", company.getId(),
        Map.of("name", company.getName(), "code", code), ip);
    return detail(company.getId());
  }

  public List<CompanySummaryView> list() {
    return companies.findAllByOrderByCreatedAtDesc().stream().map(this::summary).toList();
  }

  public CompanyDetailView getDetail(String id) {
    return detail(id);
  }

  public CompanyDetailView update(
      String id, UpdateCompanyRequest input, IhrmsPrincipal.User actor, String ip) {
    if (input.name() == null && input.status() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Provide a name or a status to update");
    }
    Company company = companies.findById(id).orElseThrow(() -> notFound("Company not found"));
    Map<String, Object> meta = new LinkedHashMap<>();
    if (input.name() != null) {
      company.setName(input.name().trim());
      meta.put("name", company.getName());
    }
    if (input.status() != null) {
      company.setStatus(input.status());
      meta.put("status", company.getStatus());
    }
    companies.save(company);
    audit(actor, id, "COMPANY_UPDATED", "Company", id, meta, ip);
    return detail(id);
  }

  public ProvisionAdminResult provisionAdmin(
      String id, ProvisionAdminRequest input, IhrmsPrincipal.User actor, String ip) {
    Company company = companies.findById(id).orElseThrow(() -> notFound("Company not found"));

    // One Company Admin per company (§2).
    if (users.existsByCompanyIdAndRole(id, UserRole.COMPANY_ADMIN)) {
      throw conflict("This company already has an admin");
    }

    String email = input.email().trim().toLowerCase();
    String tempPassword = TempPasswords.generate();
    User user = new User();
    user.setEmail(email);
    user.setName(input.name().trim());
    user.setRole(UserRole.COMPANY_ADMIN);
    user.setCompanyId(id);
    user.setPasswordHash(encoder.encode(tempPassword));
    user.setStatus("ACTIVE");
    try {
      users.save(user);
    } catch (DataIntegrityViolationException e) {
      throw conflict("Email \"" + email + "\" is already in use");
    }

    mail.sendCompanyAdminInvite(email, company.getName(), tempPassword);
    audit(actor, id, "COMPANY_ADMIN_PROVISIONED", "User", user.getId(),
        Map.of("email", email), ip);

    return new ProvisionAdminResult(adminView(user), isProd() ? null : tempPassword);
  }

  // --- mapping --------------------------------------------------------------

  private CompanyDetailView detail(String id) {
    Company company = companies.findById(id).orElseThrow(() -> notFound("Company not found"));
    User admin =
        users
            .findFirstByCompanyIdAndRoleOrderByCreatedAtAsc(id, UserRole.COMPANY_ADMIN)
            .orElse(null);
    return new CompanyDetailView(
        company.getId(),
        company.getName(),
        company.getCode(),
        company.getStatus(),
        teams.countByCompanyId(id),
        employees.countByCompanyId(id),
        admin != null,
        company.getCreatedAt().toString(),
        admin == null ? null : adminView(admin));
  }

  private CompanySummaryView summary(Company company) {
    String id = company.getId();
    return new CompanySummaryView(
        id,
        company.getName(),
        company.getCode(),
        company.getStatus(),
        teams.countByCompanyId(id),
        employees.countByCompanyId(id),
        users.existsByCompanyIdAndRole(id, UserRole.COMPANY_ADMIN),
        company.getCreatedAt().toString());
  }

  private CompanyAdminView adminView(User user) {
    return new CompanyAdminView(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getStatus(),
        user.getCreatedAt().toString());
  }

  // --- helpers --------------------------------------------------------------

  /** Upper-case + validate the employee-ID mnemonic (§5); a bad code is a 400. */
  private String normalizeCode(String raw) {
    String code = raw == null ? "" : raw.trim().toUpperCase();
    if (!EmployeeCodes.COMPANY_CODE.matcher(code).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Code must be 2-16 uppercase letters/digits, starting with a letter");
    }
    return code;
  }

  private void audit(
      IhrmsPrincipal.User actor,
      String companyId,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata,
      String ip) {
    // Partition under the TARGET company (SUPER_ADMIN actor has no company).
    audit.record(
        new AuditActor("USER", actor.userId(), companyId), action, targetType, targetId, metadata, ip);
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }

  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
