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

  /** Keys REMOVED from Form 1's display surface (§3.2) but kept in storage — carried over on re-save. */
  private static final String[] FORM1_RETIRED_KEYS = {
    "designation", "closestRelativePhone", "relationship", "workingExperiences"
  };

  /**
   * Build the {@code data} JSONB map for Form 1. The retired display fields are no longer captured,
   * but their previously stored values are CARRIED OVER so a re-save never wipes historical data
   * (columns/keys stay per the additive-only rule; offeredCtc is a column and simply stops being set).
   */
  public static Map<String, Object> toForm1Data(Form1Request r, Map<String, Object> previous) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", r.name());
    m.put("dateOfBirth", r.dateOfBirth());
    m.put("email", r.email());
    m.put("mobile", r.mobile());
    m.put("currentAddress", r.currentAddress());
    m.put("permanentAddress", r.permanentAddress());
    m.put("maritalStatus", r.maritalStatus());
    m.put("bloodGroup", r.bloodGroup());
    m.put("closestRelativeName", r.closestRelativeName());
    m.put("city", r.city());
    m.put("declaration", r.declaration());
    m.put("educationalQualifications", eduToMaps(r.educationalQualifications()));
    m.put("familyDetails", famToMaps(r.familyDetails()));
    m.put("characterReferences", refToMaps(r.characterReferences()));
    if (previous != null) {
      for (String key : FORM1_RETIRED_KEYS) {
        if (previous.get(key) != null) {
          m.put(key, previous.get(key));
        }
      }
    }
    return m;
  }

  /**
   * Form 1's view now also SURFACES the fields relocated from Form 2 (§3.2): alternate number,
   * vehicle no, PAN and account number — read from their unchanged {@code form2_info} storage
   * ({@code f2} may be null when nothing was stored yet). PAN + account keep the same
   * masked/PLAIN handling they always had.
   */
  public static Form1View form1View(Form1Personal e, Form2Info f2, Mode mode) {
    Map<String, Object> d = e.getData() == null ? Map.of() : e.getData();
    Map<String, Object> d2 = f2 == null || f2.getData() == null ? Map.of() : f2.getData();
    return new Form1View(
        str(d, "name"),
        str(d, "dateOfBirth"),
        str(d, "email"),
        str(d, "mobile"),
        str(d, "currentAddress"),
        str(d, "permanentAddress"),
        str(d2, "alternateNumber"),
        str(d2, "vehicleNo2W4W"),
        sensitive(f2 == null ? null : f2.getPanNumber(), mode),
        sensitive(f2 == null ? null : f2.getAxisAccountNumber(), mode),
        str(d, "maritalStatus"),
        str(d, "bloodGroup"),
        str(d, "closestRelativeName"),
        str(d, "city"),
        str(d, "declaration"),
        eduViews(d.get("educationalQualifications")),
        famViews(d.get("familyDetails")),
        refViews(d.get("characterReferences")),
        e.getStatus(),
        e.getRevisionNote(),
        e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
  }

  // --- Form 2 ---------------------------------------------------------------

  /** Keys relocated to Form 1's presentation but still STORED in form2_info.data (§3.2). */
  private static final String[] RELOCATED_DATA_KEYS = {
    "alternateNumber", "vehicleNo2W4W", "currentAddress", "permanentAddress"
  };

  /**
   * Keys REMOVED from Form 2's display + PDF (§3.2) but kept in storage — no longer captured, carried
   * over on re-save so previously stored values are never wiped (additive-only). These fields remain on
   * Form 1; only Form 2 stopped showing/capturing them.
   */
  private static final String[] FORM2_RETIRED_KEYS = {
    "fatherName", "dateOfBirth", "bloodGroup", "mobile", "documentSubmitted"
  };

  /**
   * Rebuild Form 2's {@code data} map from the request, CARRYING OVER the keys Form 2 no longer submits
   * — both the Form-1-relocated keys (which Form 1 reads/writes in this same map) and the retired
   * display keys — so a Form 2 re-save never wipes previously stored values.
   */
  public static Map<String, Object> toForm2Data(Form2Request r, Map<String, Object> previous) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("fullName", r.fullName());
    m.put("dateOfJoining", r.dateOfJoining());
    m.put("officialEmail", r.officialEmail());
    m.put("personalEmail", r.personalEmail());
    m.put("designation", r.designation());
    if (previous != null) {
      for (String key : RELOCATED_DATA_KEYS) {
        if (previous.get(key) != null) {
          m.put(key, previous.get(key));
        }
      }
      for (String key : FORM2_RETIRED_KEYS) {
        if (previous.get(key) != null) {
          m.put(key, previous.get(key));
        }
      }
    }
    return m;
  }

  /**
   * Write-through for the fields Form 1 now CAPTURES but whose storage stays on form2_info: the two
   * encrypted columns (PAN, account number) and the alternate-number / vehicle keys in {@code data}.
   * Only these are touched — Form 2's own fields, status and revision state are left alone.
   */
  public static void applyForm1RelocatedFields(Form2Info f2, Form1Request r) {
    f2.setPanNumber(r.panNumber());
    f2.setAxisAccountNumber(r.axisAccountNumber());
    Map<String, Object> d = new LinkedHashMap<>(f2.getData() == null ? Map.of() : f2.getData());
    d.put("alternateNumber", r.alternateNumber());
    d.put("vehicleNo2W4W", r.vehicleNo2W4W());
    f2.setData(d);
  }

  /**
   * {@code employeeId} is the minted employee code (system-assigned; null until approval). No mode:
   * Form 2 no longer carries sensitive fields — PAN + account number surface under Form 1 (§3.2).
   */
  public static Form2View form2View(Form2Info e, String employeeCode) {
    Map<String, Object> d = e.getData() == null ? Map.of() : e.getData();
    return new Form2View(
        str(d, "fullName"),
        // A minted/kept code once approved; before that an EXISTING employee's HR-entered ID (§3.2).
        employeeCode != null ? employeeCode : str(d, "employeeId"),
        str(d, "dateOfJoining"),
        str(d, "officialEmail"),
        str(d, "personalEmail"),
        str(d, "designation"),
        e.getStatus(),
        e.getRevisionNote(),
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
        e.getStatus(),
        e.getRevisionNote());
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
