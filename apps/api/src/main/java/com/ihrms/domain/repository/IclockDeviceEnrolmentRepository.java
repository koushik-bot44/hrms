package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockDeviceEnrolment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockDeviceEnrolmentRepository
    extends JpaRepository<IclockDeviceEnrolment, String> {

  Optional<IclockDeviceEnrolment> findByDeviceIdAndPinAndBioTypeAndFid(
      String deviceId, String pin, int bioType, int fid);

  /** "Enrolled on 4 of 4" reads this: every terminal that holds any finger for this pin. */
  List<IclockDeviceEnrolment> findByPin(String pin);

  /** Resolving an ack back to the enrolment it belongs to. */
  Optional<IclockDeviceEnrolment> findByCommandId(String commandId);
}
