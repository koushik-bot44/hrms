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
import com.ihrms.onboarding.dto.OnboardingDtos.CharacterReference;
import com.ihrms.onboarding.dto.OnboardingDtos.EducationalQualification;
import com.ihrms.onboarding.dto.OnboardingDtos.FamilyDetail;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.onboarding.dto.OnboardingDtos.WorkingExperience;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates the onboarding PDFs (§3.2): Forms 1/2/3 are rendered from their faithful HTML templates
 * (see {@link HtmlPdfRenderer}), Form 4 is the OpenPDF documents manifest, plus one merged complete
 * application. Each PDF is branded with the employee's JOINING company (resolved from companyId); the
 * captured signature is stamped into Forms 1 & 2; the system employeeId is left BLANK until Manager
 * approval mints it (regenerated then). Sensitive values are rendered in full — the generated PDFs are
 * themselves sensitive and reached only via short-lived presigned, audited URLs (§6).
 */
@Service
public class PdfService {

  private static final Logger log = LoggerFactory.getLogger(PdfService.class);
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final SignatureRepository signatures;
  private final GeneratedDocumentRepository generated;
  private final CompanyRepository companies;
  private final StorageService storage;
  private final HtmlPdfRenderer html;

  public PdfService(
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      SignatureRepository signatures,
      GeneratedDocumentRepository generated,
      CompanyRepository companies,
      StorageService storage,
      HtmlPdfRenderer html) {
    this.form1s = form1s;
    this.form2s = form2s;
    this.form3s = form3s;
    this.documents = documents;
    this.signatures = signatures;
    this.generated = generated;
    this.companies = companies;
    this.storage = storage;
    this.html = html;
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
    String signatureDataUri = signatureDataUri(sigImage);

    // The PDF renders real values (existing policy); Form 1's view carries the relocated fields.
    Form1View v1 = f1 == null ? null : FormMappers.form1View(f1, f2, FormMappers.Mode.PLAIN);
    Form2View v2 = f2 == null ? null : FormMappers.form2View(f2, employeeCode);
    List<Form3EntryView> v3 =
        f3.stream().map(e -> FormMappers.form3View(e, FormMappers.Mode.PLAIN)).toList();

    // Signed date: prefer when the employee signed; else fall back to the form's last-updated date.
    String signedDate = signedDate(sig, v1 == null ? null : v1.updatedAt());

    byte[] pdf1 = html.render("form1", form1Model(companyName, v1, signatureDataUri, signedDate));
    byte[] pdf2 = html.render("form2", form2Model(companyName, v2, employeeCode, signatureDataUri, signedDate));
    byte[] pdf3 = html.render("form3", form3Model(companyName, v3));
    byte[] pdf4 = PdfRenderer.form4Manifest(companyName, employeeCode, docs);
    byte[] merged = html.merge(List.of(pdf1, pdf2, pdf3, pdf4));

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

  // --- template models ------------------------------------------------------

  private Map<String, Object> form1Model(
      String companyName, Form1View v, String signatureDataUri, String signedDate) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("companyName", companyName);
    m.put("name", nn(v == null ? null : v.name()));
    m.put("dob", date(v == null ? null : v.dateOfBirth()));
    m.put("email", nn(v == null ? null : v.email()));
    m.put("mobile", nn(v == null ? null : v.mobile()));
    m.put("designation", nn(v == null ? null : v.designation()));
    m.put("offeredCtc", nn(v == null ? null : v.offeredCtc()));
    m.put("currentAddress", nn(v == null ? null : v.currentAddress()));
    m.put("permanentAddress", nn(v == null ? null : v.permanentAddress()));
    // Relocated from Form 2 (§3.2) — the PDF renders real values, per the existing PDF policy.
    m.put("alternateNumber", nn(v == null ? null : v.alternateNumber()));
    m.put("vehicleNo2W4W", nn(v == null ? null : v.vehicleNo2W4W()));
    m.put("panNumber", nn(v == null ? null : v.panNumber()));
    m.put("axisAccountNumber", nn(v == null ? null : v.axisAccountNumber()));
    m.put("maritalStatus", nn(v == null ? null : v.maritalStatus()));
    m.put("bloodGroup", nn(v == null ? null : v.bloodGroup()));
    m.put("closestRelativeName", nn(v == null ? null : v.closestRelativeName()));
    m.put("closestRelativePhone", nn(v == null ? null : v.closestRelativePhone()));
    m.put("city", nn(v == null ? null : v.city()));
    m.put("relationship", nn(v == null ? null : v.relationship()));
    m.put("declaration", nn(v == null ? null : v.declaration()));

    List<CharacterReference> refs = v == null ? List.of() : safe(v.characterReferences());
    m.put("ref1", refs.size() > 0 ? refText(refs.get(0)) : "");
    m.put("ref2", refs.size() > 1 ? refText(refs.get(1)) : "");

    List<Map<String, Object>> educations = new ArrayList<>();
    for (EducationalQualification e : v == null ? List.<EducationalQualification>of() : safe(v.educationalQualifications())) {
      educations.add(row(
          "qualification", e.qualification(),
          "university", e.university(),
          "yearOfPassing", e.yearOfPassing(),
          "percentage", e.percentage()));
    }
    m.put("educations", educations);

    List<Map<String, Object>> experiences = new ArrayList<>();
    for (WorkingExperience w : v == null ? List.<WorkingExperience>of() : safe(v.workingExperiences())) {
      experiences.add(row(
          "organization", w.organization(),
          "period", w.period(),
          "designation", w.designation(),
          "salaryCtc", w.salaryCtc(),
          "reasonForLeaving", w.reasonForLeaving()));
    }
    m.put("experiences", experiences);

    List<Map<String, Object>> families = new ArrayList<>();
    for (FamilyDetail f : v == null ? List.<FamilyDetail>of() : safe(v.familyDetails())) {
      families.add(row(
          "name", f.name(),
          "age", f.age(),
          "relation", f.relation(),
          "occupation", f.occupation()));
    }
    m.put("families", families);

    m.put("signatureDataUri", signatureDataUri);
    m.put("signedDate", nn(signedDate));
    m.put("place", nn(v == null ? null : v.city()));
    return m;
  }

  private Map<String, Object> form2Model(
      String companyName, Form2View v, String employeeCode, String signatureDataUri, String signedDate) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("companyName", companyName);
    m.put("fullName", nn(v == null ? null : v.fullName()));
    m.put("fatherName", nn(v == null ? null : v.fatherName()));
    m.put("employeeId", nn(employeeCode)); // blank until approval mints it
    m.put("dob", date(v == null ? null : v.dateOfBirth()));
    m.put("doj", date(v == null ? null : v.dateOfJoining()));
    m.put("bloodGroup", nn(v == null ? null : v.bloodGroup()));
    m.put("mobile", nn(v == null ? null : v.mobile()));
    m.put("officialEmail", nn(v == null ? null : v.officialEmail()));
    m.put("personalEmail", nn(v == null ? null : v.personalEmail()));
    m.put("designation", nn(v == null ? null : v.designation()));
    m.put("sparkId", nn(v == null ? null : v.sparkId()));
    m.put("documentSubmitted", nn(v == null ? null : v.documentSubmitted()));
    m.put("signatureDataUri", signatureDataUri);
    m.put("signedDate", nn(signedDate));
    return m;
  }

  private Map<String, Object> form3Model(String companyName, List<Form3EntryView> entries) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("companyName", companyName);
    // No company branding profile yet: address/contact stay absent (footer renders name only).
    m.put("companyAddress", null);
    m.put("companyContact", null);
    List<Map<String, Object>> employers = new ArrayList<>();
    for (Form3EntryView e : entries) {
      employers.add(row(
          "companyName", e.companyName(),
          "companyAddress", e.companyAddress(),
          "dateOfJoining", date(e.dateOfJoining()),
          "dateOfRelieving", date(e.dateOfRelieving()),
          "designation", e.designation(),
          "lastDrawnSalary", e.lastDrawnSalary(),
          "jobType", e.jobType(),
          "reasonForLeaving", e.reasonForLeaving(),
          "reportingTo", e.reportingTo(),
          "roContact", e.roContact(),
          "hrNameContact", e.hrNameContact()));
    }
    m.put("employers", employers);
    return m;
  }

  // --- helpers --------------------------------------------------------------

  private static <T> List<T> safe(List<T> list) {
    return list == null ? List.of() : list;
  }

  private static String nn(String s) {
    return s == null ? "" : s;
  }

  /** A row is a small ordered map of {field -> value}; nulls become "" so the template renders blank. */
  private static Map<String, Object> row(String... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], nn(kv[i + 1]));
    }
    return m;
  }

  private static String refText(CharacterReference r) {
    if (r == null) {
      return "";
    }
    return Stream.of(r.name(), r.address(), r.phone())
        .filter(s -> s != null && !s.isBlank())
        .reduce((a, b) -> a + ", " + b)
        .orElse("");
  }

  /** Format an ISO date ({@code YYYY-MM-DD}[...]) as {@code dd/MM/yyyy}; pass through anything else. */
  private static String date(String iso) {
    if (iso == null || iso.isBlank()) {
      return "";
    }
    try {
      return LocalDate.parse(iso.substring(0, 10)).format(DMY);
    } catch (RuntimeException e) {
      return iso;
    }
  }

  private static String signedDate(Signature sig, String fallbackIso) {
    if (sig != null && sig.getSignedAt() != null) {
      return date(sig.getSignedAt().toString());
    }
    return date(fallbackIso);
  }

  /** Build a {@code data:} image URL from the stored signature bytes (mime detected from magic bytes). */
  private static String signatureDataUri(byte[] image) {
    if (image == null || image.length == 0) {
      return null;
    }
    String mime = isJpeg(image) ? "image/jpeg" : "image/png";
    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image);
  }

  private static boolean isJpeg(byte[] b) {
    return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
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
