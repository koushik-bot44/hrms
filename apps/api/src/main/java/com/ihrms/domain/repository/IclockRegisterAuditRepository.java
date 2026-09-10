package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockRegisterAudit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockRegisterAuditRepository extends JpaRepository<IclockRegisterAudit, String> {

  /** The devices list's "last audited" column. */
  Optional<IclockRegisterAudit> findFirstByDeviceIdOrderByRequestedAtDesc(String deviceId);

  /** The audit a still-arriving dump belongs to. */
  Optional<IclockRegisterAudit> findFirstByDeviceIdAndStatusOrderByRequestedAtDesc(
      String deviceId, String status);

  List<IclockRegisterAudit> findByStatus(String status);
}
