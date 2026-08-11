package com.ihrms.review;

import com.ihrms.agreement.AgreementService;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.onboarding.FormMappers;
import com.ihrms.onboarding.OfferService;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RecordDocument;
import com.ihrms.review.dto.ReviewDtos.RecordGeneratedDocument;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import com.ihrms.storage.StorageService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link EmployeeRecordView} shared by HR (ReviewService) and Manager (ManagerService):
 * the four forms with sensitive values MASKED, the Form 4 uploads and generated PDFs with short-lived
 * presigned view URLs, and the review-complete gate. The unmasked plaintext is produced only by
 * {@link #reveal} (the explicit, audited reveal action, §6).
 */
@Component
public class EmployeeRecordAssembler {

  private static final int VIEW_TTL_SECONDS = 60;
  /** Fixed mask for sensitive values — matches PAN's mask (§6). */
  private static final String MASKED = "********";

  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final GeneratedDocumentRepository generated;
  private final CompanyRepository companies;
  private final StorageService storage;
  private final AgreementService agreementService;
  private final OfferService offerService;
  private final com.ihrms.onboarding.InviteTokenService inviteTokens;

  public EmployeeRecordAssembler(
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      GeneratedDocumentRepository generated,
      CompanyRepository companies,
      StorageService storage,
      AgreementService agreementService,
      OfferService offerService,
      com.ihrms.onboarding.InviteTokenService inviteTokens) {
    this.form1s = form1s;
    this.form2s = form2s;
    this.form3s = form3s;
    this.documents = documents;
    this.generated = generated;
    this.companies = companies;
    this.storage = storage;
    this.agreementService = agreementService;
    this.offerService = offerService;
    this.inviteTokens = inviteTokens;
  }

  public EmployeeRecordView build(Employee employee) {
    Form1Personal f1 = form1s.findByEmployeeId(employee.getId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    List<Form3PrevEmployment> f3 = form3s.findByEmployeeIdOrderByOrderIndexAsc(employee.getId());
    List<Document> docs =
        documents.findByEmployeeIdOrderByUploadedAtDesc(employee.getId()).stream()
            .filter(d -> d.getStatus() != DocumentStatus.PENDING)
            .toList();
    List<GeneratedDocument> gen = generated.findByEmployeeIdOrderByKindAsc(employee.getId());

    boolean complete = reviewComplete(employee, f1, f2, f3, docs);
    boolean revealable = f1 != null || f2 != null || !f3.isEmpty();
    String mailDomain =
        companies.findById(employee.getCompanyId()).map(Company::getMailDomain).orElse(null);

    return new EmployeeRecordView(
        employee.getId(),
        employee.getEmployeeCode(),
        employee.getFullName(),
        employee.getEmail(),
        employee.getDesignation(),
        employee.getDateOfJoining() == null ? null : employee.getDateOfJoining().toString(),
        employee.getStatus(),
        complete,
        revealable,
        employee.getCredentialsAssignedAt() != null,
        employee.getMailAddress(),
        mailDomain,
        // PAN + account number surface under Form 1 now (§3.2) — masked here; reveal stays audited.
        f1 == null ? null : FormMappers.form1View(f1, f2, FormMappers.Mode.MASKED),
        f2 == null ? null : FormMappers.form2View(f2, employee.getEmployeeCode()),
        f3.stream().map(e -> FormMappers.form3View(e, FormMappers.Mode.MASKED)).toList(),
        docs.stream().map(this::documentView).toList(),
        gen.stream().map(this::generatedView).toList(),
        employee.getAadhaarNumber() == null ? null : MASKED,
        agreementService.forRecord(employee.getId()),
        // Offer status only (§3.2) — the PDF with the salary is fetched via the role-gated endpoint, so this
        // is safe even though the shared record view reaches manager/accountant too.
        offerService.recordOffer(employee.getId()),
        employee.isAccountDeactivated(),
        // "Invite last sent" — the active invite token's createdAt; drives the HR resend surface (§3.2/§6).
        toIso(inviteTokens.sentAt(employee.getId())));
  }

  private static String toIso(java.time.Instant instant) {
    return instant == null ? null : instant.toString();
  }

  /** Plaintext sensitive values (PLAIN mode) — the caller audits this as a reveal. */
  public RevealedSensitive reveal(Employee employee) {
    Form1Personal f1 = form1s.findByEmployeeId(employee.getId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    List<Form3EntryView> f3 =
        form3s.findByEmployeeIdOrderByOrderIndexAsc(employee.getId()).stream()
            .map(e -> FormMappers.form3View(e, FormMappers.Mode.PLAIN))
            .toList();
    return new RevealedSensitive(
        f1 == null ? null : FormMappers.form1View(f1, f2, FormMappers.Mode.PLAIN),
        f2 == null ? null : FormMappers.form2View(f2, employee.getEmployeeCode()),
        f3,
        employee.getAadhaarNumber()); // decrypted by the field converter on read (§6)
  }

  public boolean reviewComplete(Employee employee) {
    Form1Personal f1 = form1s.findByEmployeeId(employee.getId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    List<Form3PrevEmployment> f3 = form3s.findByEmployeeIdOrderByOrderIndexAsc(employee.getId());
    List<Document> docs =
        documents.findByEmployeeId(employee.getId()).stream()
            .filter(d -> d.getStatus() != DocumentStatus.PENDING)
            .toList();
    return reviewComplete(employee, f1, f2, f3, docs);
  }

  /**
   * Whether every reviewable item is VERIFIED — status-AGNOSTIC (Form 2 is HR/SA-authored, §3.2, so it is
   * never part of the gate — only Forms 1, 3 and the documents are). This is the "all verified" signal that
   * drives the SUBMITTED → HR_VERIFIED auto-transition (see ReviewService.recomputeReviewStatus) and gates
   * the HR approve/reject action.
   */
  private boolean reviewComplete(
      Employee employee,
      Form1Personal f1,
      Form2Info f2,
      List<Form3PrevEmployment> f3,
      List<Document> docs) {
    return f1 != null
        && f1.getStatus() == SectionStatus.VERIFIED
        && f3.stream().allMatch(r -> r.getStatus() == SectionStatus.VERIFIED)
        && !docs.isEmpty()
        && docs.stream().allMatch(d -> d.getStatus() == DocumentStatus.VERIFIED);
  }

  private RecordDocument documentView(Document d) {
    return new RecordDocument(
        d.getId(),
        d.getDocType(),
        d.getGroupIndex(),
        d.getFileName(),
        d.getMimeType(),
        d.getSha256(),
        d.getStatus(),
        d.getRevisionNote(),
        d.getUploadedAt().toString(),
        storage.presignedGetUrl(d.getStorageKey(), VIEW_TTL_SECONDS));
  }

  private RecordGeneratedDocument generatedView(GeneratedDocument d) {
    return new RecordGeneratedDocument(
        d.getId(),
        d.getKind(),
        d.getFileName(),
        d.getSha256(),
        d.getGeneratedAt().toString(),
        storage.presignedGetUrl(d.getStorageKey(), VIEW_TTL_SECONDS));
  }
}
