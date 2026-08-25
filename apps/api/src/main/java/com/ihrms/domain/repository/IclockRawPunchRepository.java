package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockRawPunch;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockRawPunchRepository extends JpaRepository<IclockRawPunch, String> {

  /** Per-device punch history, newest first — the P0 LAN verification query. */
  List<IclockRawPunch> findBySerialNumberOrderByReceivedAtDesc(String serialNumber);

  long countBySerialNumber(String serialNumber);
}
