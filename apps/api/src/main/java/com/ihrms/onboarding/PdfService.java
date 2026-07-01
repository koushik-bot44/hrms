package com.ihrms.onboarding;

import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.model.Signature;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.SignatureRepository;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates the onboarding PDFs (§3.2) with OpenPDF: one per form (Form 1 Personal, Form 2 Info,
 * Form 3 Previous Employment, Form 4 Documents manifest) plus one merged complete application. Each
 * PDF is branded with the employee's JOINING company (resolved from companyId); the captured
 * signature is stamped into Forms 1 & 2; the system employeeId is left BLANK until Manager approval
 * mints it (regenerated then). Sensitive values are rendered in full — the generated PDFs are
 * themselves sensitive and reached only via short-lived presigned, audited URLs (§6).
 */
@Service
public class PdfService {

  private static final Logger log = LoggerFactory.getLogger(PdfService.class);

  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final SignatureRepository signatures;
  private final GeneratedDocumentRepository generated;
  private final CompanyRepository companies;
  private final StorageService storage;

  public PdfService(
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      SignatureRepository signatures,
      GeneratedDocumentRepository generated,
      CompanyRepository companies,
      StorageService storage) {
    this.form1s = form1s;
    this.form2s = form2s;
    this.form3s = form3s;
    this.documents = documents;
    this.signatures = signatures;
    this.generated = generated;
    this.companies = companies;
    this.storage = storage;
  }

  /**
   * (Re)generate all five PDFs for an employee and replace any previously generated set. Called at
   * submit and again when Manager approval mints the employee ID (so it appears on the PDFs).
   *
   * <p>Not {@code @Transactional}: it runs from a post-commit event listener (best-effort), where each
   * repository write is its own transaction — so it can never fail or roll back the submission/approval.
   */
  public void generateForEmployee(Employee employee) {
    String companyName =
        companies.findById(employee.getCompanyId()).map(Company::getName).orElse("Company");
    String employeeCode = employee.getEmployeeCode(); // null pre-approval -> blank on the PDFs

    Form1Personal f1 = form1s.findByEmployeeId(employee.getId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    List<Form3PrevEmployment> f3 = form3s.findByEmployeeIdOrderByOrderIndexAsc(employee.getId());
    List<Document> docs = documents.findByEmployeeIdOrderByUploadedAtDesc(employee.getId());

    byte[] sigImage = null;
    Signature sig = signatures.findByEmployeeId(employee.getId()).orElse(null);
    if (sig != null) {
      try {
        sigImage = storage.getObjectBytes(sig.getStorageKey());
      } catch (RuntimeException e) {
        log.warn("Could not load signature image for {}: {}", employee.getId(), e.getMessage());
      }
    }

    Form1View v1 = f1 == null ? null : FormMappers.form1View(f1, FormMappers.Mode.PLAIN);
    Form2View v2 = f2 == null ? null : FormMappers.form2View(f2, employeeCode, FormMappers.Mode.PLAIN);
    List<Form3EntryView> v3 =
        f3.stream().map(e -> FormMappers.form3View(e, FormMappers.Mode.PLAIN)).toList();

    byte[] pdf1 = PdfRenderer.form1(companyName, employeeCode, v1, sigImage);
    byte[] pdf2 = PdfRenderer.form2(companyName, employeeCode, v2, sigImage);
    byte[] pdf3 = PdfRenderer.form3(companyName, employeeCode, v3);
    byte[] pdf4 = PdfRenderer.form4Manifest(companyName, employeeCode, docs);
    byte[] merged = PdfRenderer.merge(List.of(pdf1, pdf2, pdf3, pdf4));

    // Replace the previous set (unique per employee+kind); best-effort delete of old bytes.
    for (GeneratedDocument old : generated.findByEmployeeId(employee.getId())) {
      try {
        storage.delete(old.getStorageKey());
      } catch (RuntimeException e) {
        log.warn("Could not delete old generated doc {}: {}", old.getId(), e.getMessage());
      }
    }
    generated.deleteAll(generated.findByEmployeeId(employee.getId()));

    store(employee, GeneratedDocumentKind.FORM1, "form1-personal-details.pdf", pdf1);
    store(employee, GeneratedDocumentKind.FORM2, "form2-employee-info.pdf", pdf2);
    store(employee, GeneratedDocumentKind.FORM3, "form3-previous-employment.pdf", pdf3);
    store(employee, GeneratedDocumentKind.FORM4_MANIFEST, "form4-documents.pdf", pdf4);
    store(employee, GeneratedDocumentKind.MERGED, "complete-application.pdf", merged);
  }

  private void store(Employee employee, GeneratedDocumentKind kind, String fileName, byte[] bytes) {
    String key =
        storage.buildKey(employee.getCompanyId(), employee.getId(), "generated", fileName);
    storage.putObject(key, bytes, "application/pdf");
    GeneratedDocument doc = new GeneratedDocument();
    doc.setEmployeeId(employee.getId());
    doc.setKind(kind);
    doc.setFileName(fileName);
    doc.setStorageKey(key);
    doc.setSha256(Hashing.sha256Hex(bytes));
    generated.save(doc);
  }
}
