package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockBiometricTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockBiometricTemplateRepository
    extends JpaRepository<IclockBiometricTemplate, String> {

  /** The one template for a biometric at a building. Re-enrolment replaces it rather than adding. */
  Optional<IclockBiometricTemplate> findBySiteIdAndPinAndBioTypeAndFid(
      String siteId, String pin, int bioType, int fid);

  /** Everything held for one pin at a building — what a re-sync has to push. */
  List<IclockBiometricTemplate> findBySiteIdAndPinOrderByBioTypeAscFidAsc(
      String siteId, String pin);

  List<IclockBiometricTemplate> findByPersonIdOrderByFidAsc(String personId);
}
