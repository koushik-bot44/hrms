package com.ihrms.domain.repository;

import com.ihrms.domain.model.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, String> {
  Optional<Company> findByCode(String code);

  /** Resolve a company by its URL slug, case-insensitively (slugs are stored lower-case). */
  @Query("select c from Company c where lower(c.slug) = lower(:slug)")
  Optional<Company> findBySlugIgnoreCase(@Param("slug") String slug);

  /** All in-use slugs — the taken-set for {@link com.ihrms.domain.support.CompanySlug#generate}. */
  @Query("select c.slug from Company c")
  List<String> findAllSlugs();

  List<Company> findAllByOrderByCreatedAtDesc();

  // --- Dashboard counts (status is a free string; DELETED = archived) ---
  long countByStatus(String status);

  long countByStatusNot(String status);
}
