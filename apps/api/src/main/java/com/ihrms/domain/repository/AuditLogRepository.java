package com.ihrms.domain.repository;

import com.ihrms.domain.model.AuditLog;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.Repository;

/**
 * Append-only (§7): extends the bare {@link Repository} marker and exposes ONLY insert
 * ({@code save}) + reads — there is no delete/update method. The DB also enforces this
 * with a trigger (Flyway V2), so even raw SQL cannot mutate a row. {@link JpaSpecificationExecutor}
 * adds only read methods ({@code findAll(Specification, Pageable)} / {@code count}) for the
 * filterable, paginated audit explorer — still no update/delete.
 */
public interface AuditLogRepository
    extends Repository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

  AuditLog save(AuditLog auditLog);

  Optional<AuditLog> findById(String id);

  List<AuditLog> findByCompanyId(String companyId);

  List<AuditLog> findByAction(String action);

  long count();
}
