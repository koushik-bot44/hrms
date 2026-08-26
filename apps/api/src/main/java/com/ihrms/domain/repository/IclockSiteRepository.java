package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockSite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IclockSiteRepository extends JpaRepository<IclockSite, String> {

  /** Case-insensitive name lookup, matching the {@code lower(name)} unique index in V43. */
  @Query("select s from IclockSite s where lower(s.name) = lower(:name)")
  Optional<IclockSite> findByNameIgnoreCase(@Param("name") String name);

  List<IclockSite> findAllByOrderByNameAsc();
}
