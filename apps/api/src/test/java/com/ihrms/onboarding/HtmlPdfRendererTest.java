package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Renders the Form 1/2/3 templates directly (no Spring, no DB) and asserts, via extracted text, that
 * the collected data binds into the faithful layouts: repeatable rows all render, the joining company
 * brands the letterhead/footer, sensitive values are present in full (never masked in the PDF), the
 * Form-2 Employee ID is blank pre-approval and populated once minted, and the five PDFs merge.
 */
class HtmlPdfRendererTest {

  // A valid 1x1 PNG so the signature <img> stamps without needing storage.
  private static final String PNG_1X1 =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  private final HtmlPdfRenderer renderer = new HtmlPdfRenderer();

  private static String text(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private static int pages(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return doc.getNumberOfPages();
    }
  }

  @Test
  void form1BindsFieldsRepeatablesSensitiveAndSignature() throws Exception {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("companyName", "Globex Corporation");
    m.put("name", "Meera Nair");
    m.put("dob", "05/05/1990");
    m.put("email", "meera@personal.test");
    m.put("mobile", "9812345678");
    m.put("designation", "QA Lead");
    m.put("offeredCtc", "Rs. 14,50,000"); // SENSITIVE — must appear in full
    m.put("currentAddress", "12 Marine Drive, Mumbai");
    m.put("permanentAddress", "44 Hill Road, Pune");
    // Relocated from Form 2 (§3.2) — the Form 1 document now carries them (real values, PDF policy).
    m.put("alternateNumber", "9812300000");
    m.put("vehicleNo2W4W", "MH12XY9999");
    m.put("panNumber", "ZZZPN1234Q"); // SENSITIVE — must appear in full
    m.put("axisAccountNumber", "918020099887766"); // SENSITIVE — must appear in full
    m.put("maritalStatus", "Married");
    m.put("bloodGroup", "O+");
    m.put("closestRelativeName", "Arjun Nair");
    m.put("closestRelativePhone", "9800000000");
    m.put("city", "Mumbai");
    m.put("relationship", "Spouse");
    m.put("declaration", "I DECLARE THAT THE INFORMATION IS TRUE AND CORRECT.");
    m.put("ref1", "Ravi Menon, Kochi, 9000000001");
    m.put("ref2", "Latha Rao, Chennai, 9000000002");
    m.put("educations", List.of(
        Map.of("qualification", "M.Tech", "university", "IIT Bombay", "yearOfPassing", "2014", "percentage", "8.7 CGPA"),
        Map.of("qualification", "B.Tech", "university", "COEP Pune", "yearOfPassing", "2012", "percentage", "78%"),
        Map.of("qualification", "HSC", "university", "Fergusson", "yearOfPassing", "2008", "percentage", "91%")));
    m.put("experiences", List.of(
        Map.of("organization", "Wipro", "period", "2014-2018", "designation", "QA Engineer", "salaryCtc", "8 LPA", "reasonForLeaving", "Growth"),
        Map.of("organization", "Zoho", "period", "2018-2023", "designation", "Sr QA", "salaryCtc", "12 LPA", "reasonForLeaving", "Relocation")));
    m.put("families", List.of(
        Map.of("name", "Arjun Nair", "age", "36", "relation", "Spouse", "occupation", "Architect"),
        Map.of("name", "Kavya Nair", "age", "6", "relation", "Daughter", "occupation", "Student")));
    m.put("signatureDataUri", PNG_1X1);
    m.put("signedDate", "20/07/2026");
    m.put("place", "Mumbai");

    byte[] pdf = renderer.render("form1", m);
    assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-");

    String t = text(pdf);
    assertThat(t).contains("PERSONAL DETAILS");
    assertThat(t).contains("Globex Corporation"); // joining-company letterhead
    assertThat(t).doesNotContain("Acme Technologies"); // template placeholder replaced
    assertThat(t).doesNotContain("JOINING COMPANY"); // demo sub-label removed
    assertThat(t).contains("Meera Nair").contains("QA Lead").contains("Rs. 14,50,000"); // incl. SENSITIVE
    // Relocated from Form 2 (§3.2): the Form 1 document now carries these — PAN/account in full.
    assertThat(t).contains("9812300000").contains("MH12XY9999").contains("ZZZPN1234Q").contains("918020099887766");
    assertThat(t).contains("M.Tech").contains("IIT Bombay").contains("B.Tech").contains("HSC"); // all edu rows
    assertThat(t).contains("Wipro").contains("Zoho").contains("12 LPA"); // all experience rows (+ SENSITIVE salary)
    assertThat(t).contains("Kavya Nair"); // second family row
    assertThat(t).contains("Ravi Menon").contains("Latha Rao"); // both references
    assertThat(t).contains("I DECLARE THAT THE INFORMATION IS TRUE AND CORRECT.");
  }

  @Test
  void form2EmployeeIdBlankPreApprovalThenPopulated() throws Exception {
    Map<String, Object> base = new java.util.LinkedHashMap<>();
    base.put("companyName", "Globex Corporation");
    base.put("fullName", "Meera Nair");
    base.put("fatherName", "Gopal Nair");
    base.put("dob", "05/05/1990");
    base.put("doj", "01/08/2026");
    base.put("bloodGroup", "O+");
    base.put("mobile", "9812345678");
    base.put("officialEmail", "meera@globex.test");
    base.put("personalEmail", "meera@personal.test");
    base.put("designation", "QA Lead");
    base.put("sparkId", "SPRK-9001");
    base.put("documentSubmitted", "Aadhaar, PAN");
    base.put("signatureDataUri", PNG_1X1);
    base.put("signedDate", "01/08/2026");

    // Pre-approval: employee ID blank.
    Map<String, Object> pre = new java.util.LinkedHashMap<>(base);
    pre.put("employeeId", "");
    String preText = text(renderer.render("form2", pre));
    assertThat(preText).contains("Globex Corporation");
    // PAN/account/addresses/vehicle/alternate moved to the Form 1 document (§3.2) — gone from Form 2.
    assertThat(preText).doesNotContain("ZZZPN1234Q").doesNotContain("918020099887766");
    assertThat(preText).contains("Employee ID"); // the label is present...
    assertThat(preText).doesNotContain("GLOBEX-EMP"); // ...but no minted code yet

    // Post-approval: employee ID stamped.
    Map<String, Object> post = new java.util.LinkedHashMap<>(base);
    post.put("employeeId", "GLOBEX-EMP-000007");
    assertThat(text(renderer.render("form2", post))).contains("GLOBEX-EMP-000007");
  }

  @Test
  void form3RendersOneTablePerEmployerPlusFooter() throws Exception {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("companyName", "Globex Corporation");
    m.put("companyAddress", null); // no branding profile yet
    m.put("companyContact", null);
    m.put("employers", List.of(
        employer("Wipro Ltd", "Bangalore", "Growth"),
        employer("Zoho Corp", "Chennai", "Relocation")));

    byte[] pdf = renderer.render("form3", m);
    String t = text(pdf);
    assertThat(t).contains("PREVIOUS EMPLOYMENT DETAILS");
    assertThat(t).contains("Wipro Ltd").contains("Zoho Corp"); // both employer tables
    assertThat(t).contains("Bangalore").contains("Chennai");
    assertThat(t).contains("Globex Corporation"); // footer company name
    assertThat(t).doesNotContain("Maximus Building"); // demo footer address not hardcoded
  }

  @Test
  void nullDataStillRendersValidForms() throws Exception {
    // The approval flow can regenerate before forms are completed — must still yield valid PDFs.
    byte[] f1 = renderer.render("form1", Map.of("companyName", "Globex Corporation", "educations", List.of(), "experiences", List.of(), "families", List.of()));
    byte[] f3 = renderer.render("form3", Map.of("companyName", "Globex Corporation", "employers", List.of()));
    assertThat(pages(f1)).isGreaterThanOrEqualTo(1);
    assertThat(pages(f3)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void mergeProducesSingleMultiPageDocument() throws Exception {
    byte[] f1 = renderer.render("form1", Map.of("companyName", "Globex Corporation", "educations", List.of(), "experiences", List.of(), "families", List.of()));
    byte[] f2 = renderer.render("form2", Map.of("companyName", "Globex Corporation", "employeeId", ""));
    byte[] f3 = renderer.render("form3", Map.of("companyName", "Globex Corporation", "employers", List.of()));
    byte[] merged = renderer.merge(List.of(f1, f2, f3));
    assertThat(pages(merged)).isEqualTo(pages(f1) + pages(f2) + pages(f3));
  }

  private static Map<String, Object> employer(String name, String address, String reason) {
    Map<String, Object> e = new java.util.LinkedHashMap<>();
    e.put("companyName", name);
    e.put("companyAddress", address);
    e.put("dateOfJoining", "01/06/2016");
    e.put("dateOfRelieving", "31/07/2019");
    e.put("designation", "Engineer");
    e.put("lastDrawnSalary", "Rs. 9,00,000"); // SENSITIVE
    e.put("jobType", "Permanent");
    e.put("reasonForLeaving", reason);
    e.put("reportingTo", "Manager X");
    e.put("roContact", "x@old.test");
    e.put("hrNameContact", "HR Y / 9000000000");
    return e;
  }
}
