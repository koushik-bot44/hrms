package com.ihrms.companies;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.companies.dto.CompanyDtos.CompanyDetailView;
import com.ihrms.companies.dto.CompanyDtos.CompanySummaryView;
import com.ihrms.companies.dto.CompanyDtos.CreateCompanyRequest;
import com.ihrms.companies.dto.CompanyDtos.ProvisionAdminRequest;
import com.ihrms.companies.dto.CompanyDtos.ProvisionAdminResult;
import com.ihrms.companies.dto.CompanyDtos.PurgeCompanyResult;
import com.ihrms.companies.dto.CompanyDtos.UpdateCompanyRequest;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadUpload;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadUploadRequest;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadView;
import com.ihrms.companies.dto.LetterheadDtos.MarginsRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Company management (contract §3.3). SUPER_ADMIN-only (URL rule + @PreAuthorize). POST -> 201. */
@RestController
@RequestMapping("/companies")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CompaniesController {

  private final CompaniesService companies;
  private final LetterheadService letterheads;

  public CompaniesController(CompaniesService companies, LetterheadService letterheads) {
    this.companies = companies;
    this.letterheads = letterheads;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CompanyDetailView create(
      @Valid @RequestBody CreateCompanyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.create(body, actor, request.getRemoteAddr());
  }

  @GetMapping
  public List<CompanySummaryView> list(
      @RequestParam(name = "deleted", defaultValue = "false") boolean deleted) {
    return companies.list(deleted);
  }

  @GetMapping("/{id}")
  public CompanyDetailView get(@PathVariable String id) {
    return companies.getDetail(id);
  }

  @PatchMapping("/{id}")
  public CompanyDetailView update(
      @PathVariable String id,
      @Valid @RequestBody UpdateCompanyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.update(id, body, actor, request.getRemoteAddr());
  }

  @PostMapping("/{id}/admin")
  @ResponseStatus(HttpStatus.CREATED)
  public ProvisionAdminResult provisionAdmin(
      @PathVariable String id,
      @Valid @RequestBody ProvisionAdminRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.provisionAdmin(id, body, actor, request.getRemoteAddr());
  }

  /** Archive a company (reversible soft-delete). Its principals immediately lose access (§2/§6). */
  @DeleteMapping("/{id}")
  public CompanyDetailView delete(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.delete(id, actor, request.getRemoteAddr());
  }

  @PostMapping("/{id}/restore")
  public CompanyDetailView restore(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.restore(id, actor, request.getRemoteAddr());
  }

  /**
   * PERMANENTLY delete a company and ALL of its data. Irreversible — unlike {@link #delete} this is
   * not a soft-delete and cannot be restored (§7). SUPER_ADMIN only.
   */
  @DeleteMapping("/{id}/purge")
  public PurgeCompanyResult purge(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.purge(id, actor, request.getRemoteAddr());
  }

  // --- Per-company letterhead (§3.5) — SUPER_ADMIN; document + margin box; applies to docs generated from now -

  /** Current letterhead: present flag, page size + margin box (points), a presigned preview + capability flag. */
  @GetMapping("/{id}/letterhead")
  public LetterheadView getLetterhead(@PathVariable String id) {
    return letterheads.get(id);
  }

  /** Step 1: validate type (PDF/Word) + size + get a presigned PUT for the letterhead file. */
  @PostMapping("/{id}/letterhead/begin-upload")
  public LetterheadUpload beginLetterheadUpload(
      @PathVariable String id,
      @RequestBody LetterheadUploadRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return letterheads.beginUpload(actor, id, body, request.getRemoteAddr());
  }

  /** Step 2: read → convert (Word) → normalize first page → rasterize preview → seed margins; stamp the row. */
  @PostMapping("/{id}/letterhead/confirm")
  public LetterheadView confirmLetterheadUpload(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return letterheads.confirmUpload(actor, id, request.getRemoteAddr());
  }

  /** Save the content margin box (top/bottom/left/right, points) — the Word-like margins. Audited. */
  @PutMapping("/{id}/letterhead/margins")
  public LetterheadView saveLetterheadMargins(
      @PathVariable String id,
      @RequestBody MarginsRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return letterheads.saveMargins(actor, id, body, request.getRemoteAddr());
  }

  /** Reset the margin box to the sensible defaults for the page. Audited. */
  @PostMapping("/{id}/letterhead/margins/reset")
  public LetterheadView resetLetterheadMargins(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return letterheads.resetMargins(actor, id, request.getRemoteAddr());
  }

  /** Remove the letterhead (delete objects + clear the row). Documents render plain again. Audited. */
  @DeleteMapping("/{id}/letterhead")
  public LetterheadView removeLetterhead(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return letterheads.remove(actor, id, request.getRemoteAddr());
  }
}
