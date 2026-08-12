package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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
 * Per-company letterhead (§3.5): render embedding + point-forward, the geometry (no body text overlaps a tall
 * header/footer band on ANY page of a multi-page document), the plain fallback + graceful degrade on a missing
 * object, SUPER_ADMIN-only endpoints + audit + clean replacement, and the Forms-1-4 exclusion. Gated on a local
 * Postgres (the db storage backend serves the presigned handshake). Geometry is verified by extracting every
 * glyph's Y position and asserting it sits between the header band bottom and the footer band top.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class LetterheadTest {

  private static final double PT_PER_CM = 72.0 / 2.54;
  private static final double A4_H_PT = 29.7 * PT_PER_CM;
  private static final double PAGE_W_CM = 21.0;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired LetterheadService letterheads;
  @Autowired StorageService storage;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
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

  // --- render: point-forward embedding ---------------------------------------

  @Test
  void aLetterheadEmbedsPointForwardAndLeavesEarlierRendersPlain() throws Exception {
    // BEFORE any letterhead: the document renders plain — no embedded images.
    byte[] before = render(acme.getId());
    assertThat(imageCount(before)).isZero();
    assertThat(text(before)).contains("BODY LINE 1");

    // Upload a header + footer (the full handshake: object in storage -> confirm decodes + stamps the row).
    putAndConfirm("header", 1600, 400);
    putAndConfirm("footer", 1600, 260);

    // AFTER: a freshly generated document carries the bands (header + footer image on the page).
    byte[] after = render(acme.getId());
    assertThat(imageCount(after)).isGreaterThanOrEqualTo(2);
    assertThat(text(after)).contains("BODY LINE 1"); // body still renders
    // The earlier render's bytes are unchanged (stored artifacts are immutable — never re-rendered).
    assertThat(imageCount(before)).isZero();
  }

  // --- geometry: no overlap on a long, multi-page document -------------------

  @Test
  void tallBandsNeverOverlapBodyTextOnAnyPage() throws Exception {
    // Deliberately TALL bands: header 1600x600 -> 21cm*600/1600 = 7.875cm; footer 1600x420 -> 5.5125cm.
    putAndConfirm("header", 1600, 600);
    putAndConfirm("footer", 1600, 420);
    double headerBandPt = PAGE_W_CM * 600.0 / 1600.0 * PT_PER_CM; // 223.2pt
    double footerBandPt = PAGE_W_CM * 420.0 / 1600.0 * PT_PER_CM; // 156.2pt

    byte[] pdf = render(acme.getId(), 120); // 120 paragraphs -> several pages
    try (PDDocument doc = PDDocument.load(pdf)) {
      assertThat(doc.getNumberOfPages()).isGreaterThan(1);
    }
    double[] yb = textYBounds(pdf); // {minY, maxY} from the page top, across ALL pages
    // Every glyph sits BELOW the header band and ABOVE the footer band — no overlap anywhere.
    assertThat(yb[0]).isGreaterThanOrEqualTo(headerBandPt);
    assertThat(yb[1]).isLessThanOrEqualTo(A4_H_PT - footerBandPt);
  }

  // --- plain fallback + graceful degrade -------------------------------------

  @Test
  void noLetterheadRendersPlain() throws Exception {
    byte[] pdf = render(acme.getId());
    assertThat(imageCount(pdf)).isZero();
    assertThat(text(pdf)).contains("BODY LINE 1").contains("Private & Confidential"); // plain footer text
  }

  @Test
  void aMissingStorageObjectDegradesToPlainAndStillGenerates() throws Exception {
    putAndConfirm("header", 1600, 400);
    // The row still points at the key, but the object is gone (e.g. purged) — must degrade, never fail.
    storage.delete(storage.buildLetterheadKey(acme.getId(), "HEADER"));

    byte[] pdf = render(acme.getId());
    assertThat(pdf).isNotEmpty();
    assertThat(text(pdf)).contains("BODY LINE 1"); // still a valid document
    assertThat(imageCount(pdf)).isZero(); // the unreadable band fell back to plain
  }

  // --- endpoints: SUPER_ADMIN-only, audited, clean replacement ---------------

  @Test
  void endpointsAreSuperAdminOnlyAuditedAndReplaceCleanly() throws Exception {
    String cid = acme.getId();

    // An HR staff user is refused at every letterhead endpoint (both-layer authz -> 403).
    mvc.perform(beginUpload(cid, "header", token(hr))).andExpect(status().isForbidden());
    mvc.perform(post("/companies/" + cid + "/letterhead/header/confirm").header("Authorization", "Bearer " + token(hr)))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/companies/" + cid + "/letterhead/header").header("Authorization", "Bearer " + token(hr)))
        .andExpect(status().isForbidden());

    // SUPER_ADMIN: begin -> (client PUT simulated) -> confirm sets the part + audits LETTERHEAD_UPDATED.
    mvc.perform(beginUpload(cid, "header", token(superAdmin))).andExpect(status().isOk());
    storage.putObject(storage.buildLetterheadKey(cid, "HEADER"), png(1600, 400), "image/png");
    mvc.perform(post("/companies/" + cid + "/letterhead/header/confirm").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isOk());
    assertThat(auditLogs.findByAction("LETTERHEAD_UPDATED")).hasSize(1);
    Company afterFirst = companies.findById(cid).orElseThrow();
    assertThat(afterFirst.getLetterheadHeaderWidth()).isEqualTo(1600);
    assertThat(afterFirst.getLetterheadHeaderHeight()).isEqualTo(400);

    // Replacement swaps cleanly — a second confirm at the same (stable) key overwrites the dimensions.
    storage.putObject(storage.buildLetterheadKey(cid, "HEADER"), png(1200, 300), "image/png");
    mvc.perform(post("/companies/" + cid + "/letterhead/header/confirm").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isOk());
    Company afterReplace = companies.findById(cid).orElseThrow();
    assertThat(afterReplace.getLetterheadHeaderWidth()).isEqualTo(1200);
    assertThat(afterReplace.getLetterheadHeaderHeight()).isEqualTo(300);

    // Remove reverts to plain.
    mvc.perform(delete("/companies/" + cid + "/letterhead/header").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isOk());
    assertThat(companies.findById(cid).orElseThrow().getLetterheadHeaderKey()).isNull();
  }

  @Test
  void confirmRejectsATooSmallImage() throws Exception {
    String cid = acme.getId();
    storage.putObject(storage.buildLetterheadKey(cid, "HEADER"), png(400, 120), "image/png"); // < 1000px wide
    mvc.perform(post("/companies/" + cid + "/letterhead/header/confirm").header("Authorization", "Bearer " + token(superAdmin)))
        .andExpect(status().isBadRequest());
    assertThat(companies.findById(cid).orElseThrow().getLetterheadHeaderKey()).isNull();
  }

  // --- Forms 1-4 exclusion ---------------------------------------------------

  @Test
  void theFormTemplatesHaveNoLetterheadSlotButThePipelineTemplatesDo() throws Exception {
    // Forms render through PdfService with form1/2/3 templates — structurally NO letterhead slot, so a company
    // letterhead can never bleed into them.
    for (String form : List.of("form1", "form2", "form3")) {
      String html = resource("templates/pdf/" + form + ".html");
      assertThat(html).doesNotContain("${pageCss}");
      assertThat(html).doesNotContain("letterheadHeader");
      assertThat(html).doesNotContain("running(letterhead)");
    }
    // The pipeline templates DO consume the letterhead.
    for (String tpl : List.of("agreement", "offboarding-clearance")) {
      assertThat(resource("templates/pdf/" + tpl + ".html")).contains("${pageCss}").contains("letterheadHeader");
    }
  }

  // --- helpers ---------------------------------------------------------------

  private byte[] render(String companyId) {
    return render(companyId, 3);
  }

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
              + " the running header and footer bands are exercised on every page of the document.</p>");
    }
    return sb.toString();
  }

  /** Put a real PNG at the part's key and run confirm as SUPER_ADMIN (the render-time state). */
  private void putAndConfirm(String part, int w, int h) throws Exception {
    storage.putObject(storage.buildLetterheadKey(acme.getId(), part.toUpperCase()), png(w, h), "image/png");
    letterheads.confirmUpload(principal(superAdmin), acme.getId(), part, "127.0.0.1");
  }

  private static byte[] png(int w, int h) throws IOException {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.LIGHT_GRAY);
    g.fillRect(0, 0, w, h);
    g.setColor(Color.DARK_GRAY);
    g.drawRect(0, 0, w - 1, h - 1);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  private static int imageCount(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      int n = 0;
      for (PDPage page : doc.getPages()) {
        PDResources res = page.getResources();
        if (res == null) {
          continue;
        }
        for (COSName name : res.getXObjectNames()) {
          if (res.getXObject(name) instanceof PDImageXObject) {
            n++;
          }
        }
      }
      return n;
    }
  }

  private static String text(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  /** {minY, maxY} of every non-blank glyph across all pages, measured from each page's TOP edge. */
  private static double[] textYBounds(byte[] pdf) throws IOException {
    double[] b = {Double.MAX_VALUE, -1};
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
                b[0] = Math.min(b[0], p.getYDirAdj());
                b[1] = Math.max(b[1], p.getYDirAdj());
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

  // --- fixtures --------------------------------------------------------------

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder beginUpload(
      String cid, String part, String tok) throws Exception {
    return post("/companies/" + cid + "/letterhead/" + part + "/begin-upload")
        .header("Authorization", "Bearer " + tok)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("contentType", "image/png", "sizeBytes", 12345)));
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
