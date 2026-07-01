package com.ihrms.onboarding;

import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.onboarding.dto.OnboardingDtos.CharacterReference;
import com.ihrms.onboarding.dto.OnboardingDtos.EducationalQualification;
import com.ihrms.onboarding.dto.OnboardingDtos.FamilyDetail;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3Entry;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.onboarding.dto.OnboardingDtos.WorkingExperience;
import com.ihrms.support.FieldCrypto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the four-form entities to/from their DTOs. Non-sensitive form scalars + the Form 1 child rows
 * live in the entity {@code data} JSONB; the sensitive scalars ({@code offeredCtc}, {@code panNumber},
 * {@code axisAccountNumber}) are dedicated encrypted columns, and {@code workingExperiences[].salaryCtc}
 * is encrypted within {@code data} here (the JSON column has no converter). Views are built in
 * {@code PLAIN} mode for the employee's own record (and the audited HR reveal) or {@code MASKED} for
 * the default HR/Manager view.
 */
public final class FormMappers {

  /** Whether sensitive values are returned in the clear or masked. */
  public enum Mode {
    PLAIN,
    MASKED
  }

  public static final String MASK = "********"; // ASCII mask (charset-independent across the wire)

  private FormMappers() {}

  // --- Form 1 ---------------------------------------------------------------

  /** Build the {@code data} JSONB map for Form 1 (salaryCtc encrypted; offeredCtc is a column). */
  public static Map<String, Object> toForm1Data(Form1Request r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", r.name());
    m.put("dateOfBirth", r.dateOfBirth());
    m.put("email", r.email());
    m.put("mobile", r.mobile());
    m.put("designation", r.designation());
    m.put("currentAddress", r.currentAddress());
    m.put("permanentAddress", r.permanentAddress());
    m.put("maritalStatus", r.maritalStatus());
    m.put("bloodGroup", r.bloodGroup());
    m.put("closestRelativeName", r.closestRelativeName());
    m.put("closestRelativePhone", r.closestRelativePhone());
    m.put("city", r.city());
    m.put("relationship", r.relationship());
    m.put("declaration", r.declaration());
    m.put("educationalQualifications", eduToMaps(r.educationalQualifications()));
    m.put("workingExperiences", weToMaps(r.workingExperiences()));
    m.put("familyDetails", famToMaps(r.familyDetails()));
    m.put("characterReferences", refToMaps(r.characterReferences()));
    return m;
  }

  public static Form1View form1View(Form1Personal e, Mode mode) {
    Map<String, Object> d = e.getData() == null ? Map.of() : e.getData();
    return new Form1View(
        str(d, "name"),
        str(d, "dateOfBirth"),
        str(d, "email"),
        str(d, "mobile"),
        str(d, "designation"),
        sensitive(e.getOfferedCtc(), mode),
        str(d, "currentAddress"),
        str(d, "permanentAddress"),
        str(d, "maritalStatus"),
        str(d, "bloodGroup"),
        str(d, "closestRelativeName"),
        str(d, "closestRelativePhone"),
        str(d, "city"),
        str(d, "relationship"),
        str(d, "declaration"),
        eduViews(d.get("educationalQualifications")),
        weViews(d.get("workingExperiences"), mode),
        famViews(d.get("familyDetails")),
        refViews(d.get("characterReferences")),
        e.getStatus(),
        e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
  }

  // --- Form 2 ---------------------------------------------------------------

  public static Map<String, Object> toForm2Data(Form2Request r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("fullName", r.fullName());
    m.put("fatherName", r.fatherName());
    m.put("dateOfBirth", r.dateOfBirth());
    m.put("dateOfJoining", r.dateOfJoining());
    m.put("bloodGroup", r.bloodGroup());
    m.put("mobile", r.mobile());
    m.put("alternateNumber", r.alternateNumber());
    m.put("officialEmail", r.officialEmail());
    m.put("personalEmail", r.personalEmail());
    m.put("designation", r.designation());
    m.put("documentSubmitted", r.documentSubmitted());
    m.put("vehicleNo2W4W", r.vehicleNo2W4W());
    m.put("currentAddress", r.currentAddress());
    m.put("permanentAddress", r.permanentAddress());
    return m;
  }

  /** {@code employeeId} is the minted employee code (system-assigned; null until approval). */
  public static Form2View form2View(Form2Info e, String employeeCode, Mode mode) {
    Map<String, Object> d = e.getData() == null ? Map.of() : e.getData();
    return new Form2View(
        str(d, "fullName"),
        str(d, "fatherName"),
        employeeCode,
        str(d, "dateOfBirth"),
        str(d, "dateOfJoining"),
        str(d, "bloodGroup"),
        str(d, "mobile"),
        str(d, "alternateNumber"),
        str(d, "officialEmail"),
        str(d, "personalEmail"),
        str(d, "designation"),
        e.getSparkId(),
        str(d, "documentSubmitted"),
        str(d, "vehicleNo2W4W"),
        sensitive(e.getPanNumber(), mode),
        sensitive(e.getAxisAccountNumber(), mode),
        str(d, "currentAddress"),
        str(d, "permanentAddress"),
        e.getStatus(),
        e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
  }

  // --- Form 3 ---------------------------------------------------------------

  /** Copy a request entry into an entity (encrypting lastDrawnSalary happens via the converter). */
  public static void applyForm3(Form3PrevEmployment e, Form3Entry r, int orderIndex) {
    e.setOrderIndex(orderIndex);
    e.setCompanyName(r.companyName());
    e.setCompanyAddress(r.companyAddress());
    e.setDateOfJoining(r.dateOfJoining());
    e.setDateOfRelieving(r.dateOfRelieving());
    e.setDesignation(r.designation());
    e.setLastDrawnSalary(r.lastDrawnSalary());
    e.setJobType(r.jobType());
    e.setReasonForLeaving(r.reasonForLeaving());
    e.setReportingTo(r.reportingTo());
    e.setRoContact(r.roContact());
    e.setHrNameContact(r.hrNameContact());
  }

  public static Form3EntryView form3View(Form3PrevEmployment e, Mode mode) {
    return new Form3EntryView(
        e.getId(),
        e.getCompanyName(),
        e.getCompanyAddress(),
        e.getDateOfJoining(),
        e.getDateOfRelieving(),
        e.getDesignation(),
        sensitive(e.getLastDrawnSalary(), mode),
        e.getJobType(),
        e.getReasonForLeaving(),
        e.getReportingTo(),
        e.getRoContact(),
        e.getHrNameContact(),
        e.getStatus());
  }

  // --- sensitive helpers ----------------------------------------------------

  private static String sensitive(String plaintext, Mode mode) {
    if (plaintext == null || plaintext.isBlank()) {
      return plaintext;
    }
    return mode == Mode.PLAIN ? plaintext : MASK;
  }

  // --- child-row (de)serialisation ------------------------------------------

  private static List<Map<String, Object>> eduToMaps(List<EducationalQualification> in) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (in != null) {
      for (EducationalQualification q : in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qualification", q.qualification());
        m.put("university", q.university());
        m.put("yearOfPassing", q.yearOfPassing());
        m.put("percentage", q.percentage());
        out.add(m);
      }
    }
    return out;
  }

  private static List<EducationalQualification> eduViews(Object raw) {
    List<EducationalQualification> out = new ArrayList<>();
    for (Map<String, Object> m : asList(raw)) {
      out.add(
          new EducationalQualification(
              str(m, "qualification"), str(m, "university"), str(m, "yearOfPassing"),
              str(m, "percentage")));
    }
    return out;
  }

  private static List<Map<String, Object>> weToMaps(List<WorkingExperience> in) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (in != null) {
      for (WorkingExperience w : in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organization", w.organization());
        m.put("period", w.period());
        m.put("designation", w.designation());
        m.put("salaryCtc", FieldCrypto.encrypt(w.salaryCtc())); // sensitive → encrypted in JSON
        m.put("reasonForLeaving", w.reasonForLeaving());
        out.add(m);
      }
    }
    return out;
  }

  private static List<WorkingExperience> weViews(Object raw, Mode mode) {
    List<WorkingExperience> out = new ArrayList<>();
    for (Map<String, Object> m : asList(raw)) {
      String salary = FieldCrypto.decrypt(str(m, "salaryCtc"));
      out.add(
          new WorkingExperience(
              str(m, "organization"),
              str(m, "period"),
              str(m, "designation"),
              sensitive(salary, mode),
              str(m, "reasonForLeaving")));
    }
    return out;
  }

  private static List<Map<String, Object>> famToMaps(List<FamilyDetail> in) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (in != null) {
      for (FamilyDetail f : in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.name());
        m.put("age", f.age());
        m.put("relation", f.relation());
        m.put("occupation", f.occupation());
        out.add(m);
      }
    }
    return out;
  }

  private static List<FamilyDetail> famViews(Object raw) {
    List<FamilyDetail> out = new ArrayList<>();
    for (Map<String, Object> m : asList(raw)) {
      out.add(new FamilyDetail(str(m, "name"), str(m, "age"), str(m, "relation"), str(m, "occupation")));
    }
    return out;
  }

  private static List<Map<String, Object>> refToMaps(List<CharacterReference> in) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (in != null) {
      for (CharacterReference c : in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("address", c.address());
        m.put("phone", c.phone());
        out.add(m);
      }
    }
    return out;
  }

  private static List<CharacterReference> refViews(Object raw) {
    List<CharacterReference> out = new ArrayList<>();
    for (Map<String, Object> m : asList(raw)) {
      out.add(new CharacterReference(str(m, "name"), str(m, "address"), str(m, "phone")));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asList(Object raw) {
    if (raw instanceof List<?> list) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          out.add((Map<String, Object>) m);
        }
      }
      return out;
    }
    return List.of();
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v == null ? null : v.toString();
  }
}
