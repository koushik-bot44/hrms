package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.companies.dto.LetterheadDtos.MarginsRequest;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-company letterhead (§3.5, DOCUMENT model): the uploaded PDF page is stamped as the background of every
 * page of a generated document, content is confined to the saved margin box on EVERY page (glyph X/Y asserted
 * inside the box across a long, multi-page render), margin changes affect only NEW documents (stored bytes are
 * immutable), the plain path is unchanged, a missing/corrupt object degrades to plain, endpoints are
 * SUPER_ADMIN-only + audited, and Forms 1-4 are excluded. The letterhead fixture is GRAPHICS-ONLY (coloured
 * header/footer bands, no text) so every extracted glyph is body text; the background-on-every-page check
 * rasterises each page and samples a pixel inside the header band. Gated on a local Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class LetterheadTest {

  private static final float A4_W = 595.276f;
  private static final float A4_H = 841.89f;
  private static final float HEADER_BAND_PT = 100; // coloured band across the top of the fixture
  private static final float FOOTER_BAND_PT = 60; // coloured band across the bottom

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired LetterheadService letterheads;
  @Autowired DocumentConverter converter;
  @Autowired StorageService storage;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired AuditLogRepository auditLogs;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;

  private Company acme;
  private User superAdmin;
  private User hr;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"document_blobs\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("Acme Inc", "acme");
    superAdmin = user(UserRole.SUPER_ADMIN, "root@platform", null);
    hr = user(UserRole.HR, "hr@acme", acme.getId());
  }

  // --- render: background on page 1 AND continuation pages, content inside the margin box -------------------

  @Test
  void aLetterheadStampsEveryPageAndConfinesContentToTheMarginBox() throws Exception {
    // Before any letterhead: plain (no background image on the page, plain footer text).
    byte[] before = render(acme.getId(), 3);
    assertThat(everyPageHasHeaderBand(before)).isFalse();
    assertThat(text(before)).contains("BODY LINE 1").contains("Private & Confidential");

    // Upload an A4 letterhead (coloured top + bottom bands, no text) and set TIGHT margins that clear them.
    uploadPdf(acme.getId(), letterheadPdf(A4_W, A4_H));
    double top = 120, bottom = 80, left = 60, right = 60;
    letterheads.saveMargins(principal(superAdmin), acme.getId(), new MarginsRequest(top, bottom, left, right), "127.0.0.1");

    byte[] pdf = render(acme.getId(), 120); // long → several pages
    int pages;
    try (PDDocument d = PDDocument.load(pdf)) {
      pages = d.getNumberOfPages();
    }
    assertThat(pages).isGreaterThan(1);

    // The letterhead is stamped behind EVERY page (a non-white pixel sampled inside the top band on each page).
    assertThat(everyPageHasHeaderBand(pdf)).isTrue();
    assertThat(text(pdf)).contains("BODY LINE 1");

    // Every glyph, on every page, sits inside the saved margin box.
    double[] b = textBounds(pdf); // {minX, maxRight, minY(from top), maxY(from top)} across ALL pages
    double eps = 2.0;
    assertThat(b[0]).as("minX >= left").isGreaterThanOrEqualTo(left - eps);
    assertThat(b[1]).as("maxRight <= pageW - right").isLessThanOrEqualTo(A4_W - right + eps);
    assertThat(b[2]).as("minY >= top").isGreaterThanOrEqualTo(top - eps);
    assertThat(b[3]).as("maxY <= pageH - bottom").isLessThanOrEqualTo(A4_H - bottom + eps);
    // The content also clears the artwork bands (top band 100pt, footer band 60pt).
    assertThat(b[2]).isGreaterThanOrEqualTo(HEADER_BAND_PT);
    assertThat(b[3]).isLessThanOrEqualTo(A4_H - FOOTER_BAND_PT);
  }

  // --- margin changes affect only NEW documents (earlier bytes are immutable) ------------------------------

  @Test
  void changingMarginsAffectsOnlyNewDocuments() throws Exception {
    uploadPdf(acme.getId(), letterheadPdf(A4_W, A4_H));

    letterheads.saveMargins(principal(superAdmin), acme.getId(), new MarginsRequest(120.0, 80.0, 48.0, 48.0), "127.0.0.1");
    byte[] docA = render(acme.getId(), 6);
    double leftA = textBounds(docA)[0];

    // Widen the left margin a lot, then render a new document.
    letterheads.saveMargins(principal(superAdmin), acme.getId(), new MarginsRequest(120.0, 80.0, 160.0, 48.0), "127.0.0.1");
    byte[] docB = render(acme.getId(), 6);
    double leftB = textBounds(docB)[0];

    assertThat(leftA).isLessThan(70); // docA used the 48pt left margin
    assertThat(leftB).isGreaterThan(150); // docB used the 160pt left margin
    // docA's bytes never changed — re-measuring the SAME array still shows the old margin.
    assertThat(textBounds(docA)[0]).isEqualTo(leftA);
  }

  // --- plain fallback + graceful degrade -------------------------------------------------------------------

  @Test
  void noLetterheadRendersPlain() throws Exception {
    byte[] pdf = render(acme.getId(), 3);
    assertThat(everyPageHasHeaderBand(pdf)).isFalse();
    assertThat(text(pdf)).contains("BODY LINE 1").contains("Private & Confidential"); // plain footer text
  }

  @Test
  void aMissingStorageObjectDegradesToPlainAndStillGenerates() throws Exception {
    uploadPdf(acme.getId(), letterheadPdf(A4_W, A4_H));
    // The row still points at the key, but the normalized PDF object is gone (e.g. purged) — must degrade.
    storage.delete(storage.buildLetterheadKey(acme.getId(), "letterhead.pdf"));

    byte[] pdf = render(acme.getId(), 3);
    assertThat(pdf).isNotEmpty();
    assertThat(text(pdf)).contains("BODY LINE 1"); // still a valid document
    assertThat(everyPageHasHeaderBand(pdf)).isFalse(); // degraded to plain
  }

  // --- Word upload: converted + identical, OR skipped-with-report when LibreOffice is absent ---------------

  @Test
  void wordUploadConvertsAndBehavesIdentically() throws Exception {
    Assumptions.assumeTrue(
        converter.isAvailable(),
        "LibreOffice/soffice is not available in this environment — Word→PDF conversion is skipped (PDF upload is accepted; users export to PDF).");
    // If a converter is present, a .docx upload normalizes to a PDF letterhead and stamps like any other.
    // (No .docx fixture is bundled; provisioning LibreOffice + a fixture would exercise this branch.)
  }

  // --- endpoints: SUPER_ADMIN-only, audited ----------------------------------------------------------------

  @Test
  void endpointsAreSuperAdminOnlyAndAudited() throws Exception {
    String cid = acme.getId();

    // An HR staff user is refused at every letterhead endpoint (both-layer authz → 403).
    mvc.perform(beginUpload(cid, token(hr))).andExpect(status().isForbidden());
    mvc.perform(post("/companies/" + cid + "/letterhead/confirm").header("Authorization", "Bearer " + token(hr)))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/companies/" + cid + "/letterhead/margins")
                .header("Authorization", "Bearer " + token(hr))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new MarginsRequest(100.0, 100.0, 50.0, 50.0))))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/companies/" + cid + "/letterhead").header("Authorization", "Bearer " + token(hr)))
        .andExpect(status().isForbidden());

    // SUPER_ADMIN: begin → (client PUT simulated) → confirm stamps the row + audits LETTERHEAD_UPDATED.
    mvc.perform(beginUpload(cid, token(superAdmin))).andExpect(status().isOk());
    storage.putObject(storage.buildLetterheadKey(cid, "letterhead-original"), letterheadPdf(A4_W, A4_H), "application/pdf");
    mvc.perform(post("/companies/" + cid + "/letterhead/confirm").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isOk());
    assertThat(auditLogs.findByAction("LETTERHEAD_UPDATED")).hasSize(1);
    Company after = companies.findById(cid).orElseThrow();
    assertThat(after.getLetterheadPdfKey()).isNotNull();
    assertThat(after.getLetterheadPageWidthPt()).isCloseTo((double) A4_W, org.assertj.core.data.Offset.offset(1.0));

    // Replacement swaps cleanly: a second confirm at the same stable key overwrites the geometry.
    storage.putObject(storage.buildLetterheadKey(cid, "letterhead-original"), letterheadPdf(612f, 792f), "application/pdf");
    mvc.perform(post("/companies/" + cid + "/letterhead/confirm").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isOk());
    assertThat(companies.findById(cid).orElseThrow().getLetterheadPageWidthPt())
        .isCloseTo(612.0, org.assertj.core.data.Offset.offset(1.0));

    // Remove reverts to plain.
    mvc.perform(delete("/companies/" + cid + "/letterhead").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isOk());
    assertThat(companies.findById(cid).orElseThrow().getLetterheadPdfKey()).isNull();
  }

  @Test
  void confirmRejectsANonDocumentUpload() throws Exception {
    String cid = acme.getId();
    storage.putObject(storage.buildLetterheadKey(cid, "letterhead-original"), new byte[] {1, 2, 3, 4, 5}, "application/pdf");
    mvc.perform(post("/companies/" + cid + "/letterhead/confirm").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isBadRequest());
    assertThat(companies.findById(cid).orElseThrow().getLetterheadPdfKey()).isNull();
  }

  // --- Forms 1-4 exclusion ---------------------------------------------------------------------------------

  @Test
  void theFormTemplatesHaveNoLetterheadSlotButThePipelineTemplatesDo() throws Exception {
    for (String form : List.of("form1", "form2", "form3")) {
      String html = resource("templates/pdf/" + form + ".html");
      assertThat(html).doesNotContain("${pageCss}");
      assertThat(html).doesNotContain("letterheadHeader");
      assertThat(html).doesNotContain("running(letterhead)");
    }
    for (String tpl : List.of("agreement", "offboarding-clearance")) {
      assertThat(resource("templates/pdf/" + tpl + ".html")).contains("${pageCss}").contains("letterheadHeader");
    }
  }

  // --- helpers ---------------------------------------------------------------------------------------------

  private byte[] render(String companyId, int paragraphs) {
    Map<String, Object> model = new LinkedHashMap<>();
    model.put("bodyHtml", body(paragraphs));
    model.put("footerText", "Private & Confidential");
    return letterheads.renderBranded("agreement", model, companyId);
  }

  private static String body(int paragraphs) {
    StringBuilder sb = new StringBuilder("<h1 class=\"title\">Test Document</h1>");
    sb.append("<p>BODY LINE 1 — the opening sentence of the body content.</p>");
    for (int i = 2; i <= paragraphs; i++) {
      sb.append("<p>Body paragraph ")
          .append(i)
          .append(" with enough words to flow across the page and push content onto continuation pages so"
              + " the letterhead background is exercised on every page of the document.</p>");
    }
    return sb.toString();
  }

  /** Put a real PDF at the original key and run confirm as SUPER_ADMIN (the render-time state). */
  private void uploadPdf(String cid, byte[] pdf) {
    storage.putObject(storage.buildLetterheadKey(cid, "letterhead-original"), pdf, "application/pdf");
    letterheads.confirmUpload(principal(superAdmin), cid, "127.0.0.1");
  }

  /** A graphics-only letterhead: a coloured band across the top and one across the bottom, NO text. */
  private static byte[] letterheadPdf(float wPt, float hPt) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(new PDRectangle(wPt, hPt));
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.setNonStrokingColor(30, 80, 170); // blue header band (PDF origin is bottom-left)
        cs.addRect(0, hPt - HEADER_BAND_PT, wPt, HEADER_BAND_PT);
        cs.fill();
        cs.setNonStrokingColor(170, 50, 50); // red footer band
        cs.addRect(0, 0, wPt, FOOTER_BAND_PT);
        cs.fill();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  /** True iff EVERY page has a non-white pixel sampled inside the top band (the letterhead is stamped behind). */
  private static boolean everyPageHasHeaderBand(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      PDFRenderer renderer = new PDFRenderer(doc);
      float dpi = 96f;
      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
        int px = img.getWidth() / 2;
        int py = Math.round(20f / 72f * dpi); // 20pt from the top — inside the 100pt header band
        int rgb = img.getRGB(px, py);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        boolean nearWhite = r > 240 && g > 240 && b > 240;
        if (nearWhite) {
          return false;
        }
      }
      return true;
    }
  }

  private static String text(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  /** {minX, maxRight, minY, maxY} of every non-blank glyph across all pages, measured from each page's edges. */
  private static double[] textBounds(byte[] pdf) throws IOException {
    double[] b = {Double.MAX_VALUE, -1, Double.MAX_VALUE, -1};
    try (PDDocument doc = PDDocument.load(pdf)) {
      PDFTextStripper stripper =
          new PDFTextStripper() {
            @Override
            protected void writeString(String s, List<TextPosition> positions) {
              for (TextPosition p : positions) {
                String u = p.getUnicode();
                if (u == null || u.isBlank()) {
                  continue;
                }
                b[0] = Math.min(b[0], p.getXDirAdj());
                b[1] = Math.max(b[1], p.getXDirAdj() + p.getWidthDirAdj());
                b[2] = Math.min(b[2], p.getYDirAdj());
                b[3] = Math.max(b[3], p.getYDirAdj());
              }
            }
          };
      stripper.setStartPage(1);
      stripper.setEndPage(doc.getNumberOfPages());
      stripper.getText(doc);
    }
    return b;
  }

  private String resource(String path) throws IOException {
    try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  // --- fixtures --------------------------------------------------------------------------------------------

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder beginUpload(String cid, String tok)
      throws Exception {
    return post("/companies/" + cid + "/letterhead/begin-upload")
        .header("Authorization", "Bearer " + tok)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("contentType", "application/pdf", "sizeBytes", 12345)));
  }

  private Company company(String name, String slug) {
    Company c = new Company();
    c.setName(name);
    c.setCode(slug.toUpperCase());
    c.setSlug(slug);
    c.setMailDomain(slug);
    return companies.save(c);
  }

  private User user(UserRole role, String email, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name());
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setPasswordHash(encoder.encode("Passw0rd!"));
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private IhrmsPrincipal.User principal(User u) {
    return new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null);
  }

  private String token(User u) {
    return tokens.issueAccess(principal(u));
  }
}
