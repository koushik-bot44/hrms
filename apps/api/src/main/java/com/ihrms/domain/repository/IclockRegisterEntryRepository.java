package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockRegisterEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockRegisterEntryRepository extends JpaRepository<IclockRegisterEntry, String> {

  List<IclockRegisterEntry> findByAuditIdOrderByPinAsc(String auditId);

  void deleteByAuditId(String auditId);
}
