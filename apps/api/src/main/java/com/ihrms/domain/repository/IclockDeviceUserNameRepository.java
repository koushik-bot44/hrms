package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockDeviceUserName;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockDeviceUserNameRepository
    extends JpaRepository<IclockDeviceUserName, String> {

  Optional<IclockDeviceUserName> findByDeviceIdAndPin(String deviceId, String pin);

  /** Everything one terminal calls its users — the audit's NAME DRIFT column. */
  List<IclockDeviceUserName> findByDeviceId(String deviceId);
}
