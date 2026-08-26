package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockDevice;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockDeviceRepository extends JpaRepository<IclockDevice, String> {

  /** The serial is the device's only identity; it is uniquely indexed (V41). */
  Optional<IclockDevice> findBySerialNumber(String serialNumber);

  /** Population by claim state — the input to the self-announce cap. */
  long countByStatus(String status);

  java.util.List<IclockDevice> findBySiteId(String siteId);
}
