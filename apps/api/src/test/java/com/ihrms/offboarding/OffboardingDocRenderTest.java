package com.ihrms.offboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.onboarding.HtmlPdfRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Fidelity + template de-risk (no Spring, no DB) for the offboarding documents (§3.6 stage 2): render each
 * document + the clearance form and assert, via extracted text, that the tokens substitute, the source company
 * name / HR name are fully replaced, the per-case values land, and the faithful legal text (incl. the distinctive
 * penalty + governing-law clauses) is present. The WHEREAS placeholder must be ABSENT from Separation.
 */
class OffboardingDocRenderTest {

  private static final String PNG_1X1 =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  private final OffboardingDocumentTemplates templates = new OffboardingDocumentTemplates();
  private final HtmlPdfRenderer renderer = new HtmlPdfRenderer();

  private static String text(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private byte[] render(OffboardingDocType type, Map<String, String> tokens) {
    String sig = "<img class=\"sig\" src=\"" + PNG_1X1 + "\" alt=\"\"/>";
    String body = templates.render(type, tokens, sig);
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("bodyHtml", body);
    model.put("footerText", "Private & Confidential");
    return renderer.render("agreement", model);
  }

  private static Map<String, String> tokens(String company, String hr) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("COMPANY_NAME", company);
    t.put("HR_NAME", hr);
    t.put("EMPLOYEE_NAME", "Meera Nair");
    t.put("SIGN_DATE", "20/07/2026");
    // Settlement HR + employee values
    t.put("AGREEMENT_DATE", "01/07/2026");
    t.put("EMPLOYMENT_START", "01/08/2020");
    t.put("LAST_DATE", "30/09/2026");
    t.put("DESIGNATION", "Software Engineer");
    t.put("SETTLEMENT_TERMS_LINE", "Full and final salary settled after statutory deductions.");
    t.put("FATHER_NAME", "Rajan Nair");
    t.put("AGE", "31");
    t.put("ADDRESS", "12 Marine Drive, Mumbai");
    // Separation HR values
    t.put("RESIGNED_DATE", "30/09/2026");
    t.put("EMPLOYMENT_AGREEMENT_DATE", "01/08/2020");
    t.put("GARDEN_LEAVE_START", "26th September 2026");
    t.put("PAYMENT_AMOUNT", "Rs. 52,000");
    t.put("COMPANY_CAR", "[NA]");
    t.put("CAR_PLATE", "[NA]");
    t.put("PLACE", "Hyderabad");
    return t;
  }

  @Test
  void exitFormalitiesKeepsAuditBulletsAndAcknowledgement() throws Exception {
    String t = text(render(OffboardingDocType.EXIT_FORMALITIES, tokens("Globex Corporation", "")));
    assertThat(t).contains("Separation & Exit Formalities");
    assertThat(t).contains("Post-employment data verification");
    assertThat(t).contains("Data Loss Prevention (DLP) review");
    assertThat(t).contains("ACKNOWLEDGEMENT");
    assertThat(t).contains("Meera Nair");
  }

  @Test
  void settlementSubstitutesCompanyHrAndKeepsPenalty() throws Exception {
    String t = text(render(OffboardingDocType.SETTLEMENT, tokens("Globex Corporation", "Asha Rao")));
    assertThat(t).contains("SETTLEMENT AGREEMENT");
    assertThat(t).contains("Globex Corporation").doesNotContain("SCREATIVES");
    assertThat(t).contains("Asha Rao").doesNotContain("Kiran Thakur");
    assertThat(t).contains("Rs.10,00,000/-"); // static penalty
    assertThat(t).contains("Full and final salary settled after statutory deductions."); // the terms line
    assertThat(t).contains("Software Engineer").contains("Rajan Nair").contains("12 Marine Drive, Mumbai");
    // Registered office stays static template text (v1).
    assertThat(t).contains("Orian Towers");
  }

  @Test
  void separationSubstitutesDropsWhereasKeepsGoverningLaw() throws Exception {
    String t = text(render(OffboardingDocType.SEPARATION, tokens("Globex Corporation", "Asha Rao")));
    assertThat(t).contains("EMPLOYEE SEPARATION AGREEMENT AND RELEASE");
    assertThat(t).contains("Globex Corporation").doesNotContain("SCREATIVES");
    assertThat(t).contains("Asha Rao").doesNotContain("KIRAN THAKUR");
    assertThat(t).contains("INR 2,00,000"); // static penalty
    assertThat(t).contains("Telangana").contains("Hyderabad"); // governing law static
    assertThat(t).contains("Rs. 52,000"); // HR payment amount
    // The WHEREAS placeholder line is dropped entirely (v1).
    assertThat(t).doesNotContain("INSERT WHEREAS");
  }

  @Test
  void companyNameSubstitutesPerCompany() throws Exception {
    String alpha = text(render(OffboardingDocType.SETTLEMENT, tokens("Alpha Systems", "H R")));
    String beta = text(render(OffboardingDocType.SETTLEMENT, tokens("Beta Labs", "H R")));
    assertThat(alpha).contains("Alpha Systems").doesNotContain("Beta Labs");
    assertThat(beta).contains("Beta Labs").doesNotContain("Alpha Systems");
  }

  @Test
  void clearanceFormRendersSectionsAndDetails() throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("companyName", "Globex Corporation");
    m.put("companyAddress", "");
    m.put("footerText", "Private & Confidential");
    m.put("d_name", "Meera Nair");
    m.put("d_employeeId", "GLBX-EMP-000001");
    m.put("d_department", "Engineering");
    m.put("d_designation", "Software Engineer");
    m.put("d_manager", "Mgr One");
    m.put("d_lastWorkingDay", "2026-09-30");
    List<Map<String, Object>> sections = new ArrayList<>();
    for (ClearanceSpec.Section s : ClearanceSpec.SECTIONS) {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (ClearanceSpec.Item it : s.items()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", it.label());
        row.put("yes", true);
        row.put("no", false);
        row.put("remarks", "ok");
        rows.add(row);
      }
      Map<String, Object> sec = new LinkedHashMap<>();
      sec.put("title", s.title());
      sec.put("returned", s.kind() == ClearanceSpec.ItemKind.RETURNED);
      sec.put("rows", rows);
      sections.add(sec);
    }
    m.put("sections", sections);
    m.put("finalSignoffYes", true);
    m.put("finalSignoffNo", false);
    m.put("statusApproved", true);
    m.put("statusPending", false);
    m.put("statusOnHold", false);

    String t = text(renderer.render("offboarding-clearance", m));
    assertThat(t).containsIgnoringCase("Employee Off-Boarding Clearance Form");
    assertThat(t).contains("Meera Nair").contains("GLBX-EMP-000001");
    assertThat(t).contains("HR / Clearance").contains("IT Access Deactivation").contains("Data Verification");
    assertThat(t).contains("Final Clearance Status");
    assertThat(t).contains("No Suspicious Data Transfer Detected"); // a distinctive data-verification item
  }
}
