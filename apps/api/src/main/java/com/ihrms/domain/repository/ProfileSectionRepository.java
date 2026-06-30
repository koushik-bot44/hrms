package com.ihrms.domain.repository;

import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.model.ProfileSection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileSectionRepository extends JpaRepository<ProfileSection, String> {
  List<ProfileSection> findByEmployeeId(String employeeId);

  Optional<ProfileSection> findByEmployeeIdAndKey(String employeeId, SectionKey key);
}
