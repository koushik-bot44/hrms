package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockDeviceCommand;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockDeviceCommandRepository extends JpaRepository<IclockDeviceCommand, String> {

  /**
   * The serve query: the oldest thing still waiting for this terminal.
   *
   * <p>One at a time on purpose. This firmware acknowledges by id, so serving a batch would leave the
   * server unable to say which of them actually landed.
   */
  Optional<IclockDeviceCommand> findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(
      String deviceId, String status);

  /** The per-device queue cap — how much is already waiting before another is accepted. */
  long countByDeviceIdAndStatus(String deviceId, String status);

  /** The console's command log for one terminal, newest first. */
  List<IclockDeviceCommand> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

  /** Everything still outstanding anywhere — the "is the fleet stuck" question. */
  List<IclockDeviceCommand> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
}
