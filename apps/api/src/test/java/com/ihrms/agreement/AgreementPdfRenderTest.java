package com.ihrms.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.enums.EmployeeAgreementType;
import com.ihrms.onboarding.HtmlPdfRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Fidelity + template de-risk (no Spring, no DB): renders each agreement from its single-source body
 * fragment through the generic {@code agreement} template and asserts, via extracted text, that the token
 * substitutions land, the source company name is fully replaced, the signed values stamp, and the faithful
 * legal text (incl. the Notice Do's &amp; Don'ts table and NDA governing-law clauses) is present.
 */
class AgreementPdfRenderTest {

  private static final String PNG_1X1 =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  private final AgreementTemplates templates = new AgreementTemplates();
  private final HtmlPdfRenderer renderer = new HtmlPdfRenderer();

  private static String text(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private byte[] renderPdf(EmployeeAgreementType type, Map<String, String> tokens, String sigImg) {
    String body = templates.render(type, tokens, sigImg);
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("bodyHtml", body);
    model.put("footerText", "Private & Confidential");
    return renderer.render("agreement", model);
  }

  private static Map<String, String> baseTokens(String company, String hr) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("COMPANY_NAME", company);
    t.put("HR_NAME", hr);
    t.put("EMPLOYEE_NAME", "Meera Nair");
    t.put("EMPLOYEE_ID", "GLOBEX-EMP-000007");
    t.put("DESIGNATION", "Software Engineer");
    t.put("ADDRESS", "12 Marine Drive, Mumbai");
    t.put("MOBILE", "9812345678");
    t.put("AADHAAR", "1234 5678 9012");
    t.put("SIGN_DATE", "20/07/2026");
    t.put("SIGN_DATE_LONG", "3rd day of September 2026");
    return t;
  }

  private static String sigImg() {
    return "<img class=\"sig\" src=\"" + PNG_1X1 + "\" alt=\"signature\"/>";
  }

  @Test
  void aupSubstitutesCompanyNameStampsAadhaarAndKeepsFaithfulText() throws Exception {
    byte[] pdf = renderPdf(EmployeeAgreementType.AUP, baseTokens("Globex Corporation", ""), sigImg());
    assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    String t = text(pdf);
    assertThat(t).contains("ACCEPTABLE USE POLICY");
    // Every "SCREATIVES SOFTWARE SERVICES" occurrence -> the joining company name.
    assertThat(t).contains("Globex Corporation");
    assertThat(t).doesNotContain("SCREATIVES");
    // The distinctive Monitoring clause, verbatim.
    assertThat(t).contains("reserves the right to monitor usage of its systems");
    // Acknowledgement fill block.
    assertThat(t).contains("ACKNOWLEDGEMENT");
    assertThat(t).contains("Meera Nair").contains("GLOBEX-EMP-000007");
    assertThat(t).contains("1234 5678 9012"); // Aadhaar stamped in full
    assertThat(t).contains("Aadhaar No.");
  }

  @Test
  void ndaStampsSendingHrNameDesignationAndKeepsGoverningLaw() throws Exception {
    byte[] pdf = renderPdf(EmployeeAgreementType.NDA, baseTokens("Globex Corporation", "Asha Rao"), sigImg());
    String t = text(pdf);
    assertThat(t).contains("NON-DISCLOSURE");
    assertThat(t).contains("Globex Corporation");
    assertThat(t).doesNotContain("SCREATIVES");
    // Company-block signatory is the SENDING HR, not the source's "(Kiran Thakur)".
    assertThat(t).contains("Asha Rao");
    assertThat(t).doesNotContain("Kiran Thakur");
    assertThat(t).contains("Manager HR"); // static designation retained
    // Governing law + jurisdiction stay static.
    assertThat(t).contains("Telangana").contains("Hyderabad");
    // Date renders into the day/month sentence; designation into "Employee as ___".
    assertThat(t).contains("3rd day of September 2026");
    assertThat(t).contains("Software Engineer");
    assertThat(t).contains("12 Marine Drive, Mumbai").contains("9812345678");
    assertThat(t).contains("twelve (12) months"); // Non-Compete clause, verbatim
  }

  @Test
  void noticeRendersGuidelinesAndDosAndDontsTable() throws Exception {
    byte[] pdf = renderPdf(EmployeeAgreementType.NOTICE_PERIOD, baseTokens("Globex Corporation", ""), sigImg());
    String t = text(pdf);
    assertThat(t).contains("NOTICE PERIOD CONDUCT GUIDELINES");
    assertThat(t).contains("Employee Acknowledgement");
    assertThat(t).contains("Meera Nair");
    // The Do's & Don'ts table.
    assertThat(t).contains("DO");
    assertThat(t).contains("Do not take unauthorized or unplanned leave.");
    assertThat(t).contains("Bharatiya Nyaya Sanhita, 2023"); // clause 11, verbatim
  }

  @Test
  void companyNameSubstitutesIndependentlyPerCompany() throws Exception {
    String alpha = text(renderPdf(EmployeeAgreementType.AUP, baseTokens("Alpha Systems", ""), sigImg()));
    String beta = text(renderPdf(EmployeeAgreementType.AUP, baseTokens("Beta Labs", ""), sigImg()));
    assertThat(alpha).contains("Alpha Systems").doesNotContain("Beta Labs");
    assertThat(beta).contains("Beta Labs").doesNotContain("Alpha Systems");
  }
}
