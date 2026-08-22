package com.ihrms.domain.repository;

import com.ihrms.domain.model.Team;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamRepository extends JpaRepository<Team, String> {
  List<Team> findByCompanyId(String companyId);

  /** Batched team lookup for a set of companies — used by the hierarchy offboarding history. */
  List<Team> findByCompanyIdIn(Collection<String> companyIds);

  List<Team> findByCompanyIdOrderByCreatedAtDesc(String companyId);

  long countByCompanyId(String companyId);

  Optional<Team> findByIdAndCompanyId(String id, String companyId);

  /** The HR's own team (its Manager is the approver for that HR's onboarded employees, §2). */
  Optional<Team> findByCompanyIdAndHrUserId(String companyId, String hrUserId);

  /** The teams a Manager runs (for team-scoped dashboard counts). */
  List<Team> findByManagerUserId(String managerUserId);

  /** True iff this manager manages a team whose HR onboarded the employee (§6 manager scope). */
  boolean existsByCompanyIdAndManagerUserIdAndHrUserId(
      String companyId, String managerUserId, String hrUserId);

  /** The team(s) an Accountant reads (for its team-scoped views); exactly one in practice. */
  List<Team> findByAccountantUserId(String accountantUserId);

  /** True iff this accountant's team is the one whose HR onboarded the employee (§6 accountant scope). */
  boolean existsByCompanyIdAndAccountantUserIdAndHrUserId(
      String companyId, String accountantUserId, String hrUserId);

  /** Teams per company: {@code [companyId, Long]} rows (Hierarchy companies list, §2). */
  @Query("select t.companyId, count(t) from Team t group by t.companyId")
  List<Object[]> countGroupByCompany();

  /**
   * Staff-slot coverage per company (Hierarchy org browser, §2): {@code [companyId, hrFilled, managersFilled,
   * accountantsFilled, teams]} — {@code count(column)} counts only non-null slots. One GROUP BY, no N+1.
   */
  @Query(
      "select t.companyId, count(t.hrUserId), count(t.managerUserId), count(t.accountantUserId), count(t)"
          + " from Team t group by t.companyId")
  List<Object[]> staffCoverageGroupByCompany();
}
