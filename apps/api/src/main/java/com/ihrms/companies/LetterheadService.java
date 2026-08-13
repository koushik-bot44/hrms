package com.ihrms.companies;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadUpload;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadUploadRequest;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadView;
import com.ihrms.companies.dto.LetterheadDtos.MarginsRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.onboarding.HtmlPdfRenderer;
import com.ihrms.storage.StorageService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-company letterhead (§3.5, DOCUMENT model). The SUPER_ADMIN uploads ONE letterhead file (PDF, or Word
 * converted to PDF); its FIRST PAGE becomes the page background of every generated PIPELINE document (offer,
 * agreements, offboarding documents, clearance, relieving/experience letters — Forms 1-4 EXCLUDED), and a
 * Word-like MARGIN BOX (top/bottom/left/right, points) sets where content may sit. Documents generated FROM
 * THEN ON print onto the letterhead within those margins; already-generated PDFs are immutable and untouched.
 *
 * <p>Two responsibilities: (1) the upload handshake — presigned PUT (begin) → read/convert/normalize/rasterize
 * (confirm) → the company row carries the normalized-PDF key + page size + a default margin box; plus margin
 * saves. (2) the render seam — {@link #renderBranded} renders the content to PDF at the letterhead's page size
 * with the saved margins (no bands), then STAMPS the letterhead behind every page via PDFBox. Any failure —
 * a missing/corrupt object, conversion, or the overlay itself — DEGRADES to the plain layout and still returns
 * a PDF (a document is never failed because branding is missing).
 */
@Service
public class LetterheadService {

  private static final Logger log = LoggerFactory.getLogger(LetterheadService.class);

  static final int MAX_BYTES = 10 * 1024 * 1024; // 10 MB
  static final int UPLOAD_TTL = 300; // 5 min presigned PUT
  static final int PREVIEW_TTL = 300; // 5 min presigned GET
  static final float PREVIEW_DPI = 150f;

  private static final double PT_PER_CM = 72.0 / 2.54;
  // Sane page bounds (points): ~A5 short side up to ~A3 long side. Other sizes are ACCEPTED as-is (rendered at
  // the letterhead's own size so the overlay still aligns 1:1) — only the absurd is rejected.
  private static final double MIN_PAGE_PT = 200; // ~7cm
  private static final double MAX_PAGE_PT = 2000; // ~70cm
  private static final double MIN_CONTENT_PT = 72; // keep at least 1in of content each way
  private static final double SIDE_DEFAULT_PT = 1.7 * PT_PER_CM; // 1.7cm side gutter (matches the plain layout)
  private static final double DEFAULT_TOP_FRACTION = 0.25; // guides seed at 25% from top …
  private static final double DEFAULT_BOTTOM_FRACTION = 0.15; // … and 15% from the bottom

  private static final String PDF_TYPE = "application/pdf";
  private static final String DOCX_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  private static final String DOC_TYPE = "application/msword";

  /** The exact @page/#letterhead/#footer rules the templates used before letterheads — the plain fallback. */
  static final String PLAIN_PAGE_CSS =
      "@page{size:A4;margin:2.7cm 1.7cm 2.1cm 1.7cm;"
          + "@top-center{content:element(letterhead)}@bottom-center{content:element(footer)}}"
          + "#letterhead{position:running(letterhead);height:1.7cm;width:100%}"
          + "#footer{position:running(footer);width:100%;text-align:center;font-size:7.5px;color:#999;"
          + "border-top:0.5px solid #ddd;padding-top:3px}";

  private final CompanyRepository companies;
  private final StorageService storage;
  private final AuditService audit;
  private final HtmlPdfRenderer html;
  private final DocumentConverter converter;

  public LetterheadService(
      CompanyRepository companies,
      StorageService storage,
      AuditService audit,
      HtmlPdfRenderer html,
      DocumentConverter converter) {
    this.companies = companies;
    this.storage = storage;
    this.audit = audit;
    this.html = html;
    this.converter = converter;
  }

  private String originalKey(String cid) {
    return storage.buildLetterheadKey(cid, "letterhead-original");
  }

  private String pdfKey(String cid) {
    return storage.buildLetterheadKey(cid, "letterhead.pdf");
  }

  private String previewKey(String cid) {
    return storage.buildLetterheadKey(cid, "letterhead-preview.png");
  }

  // --- SUPER_ADMIN upload handshake ------------------------------------------

  /** Validate the declared type/size, then presign a PUT to the STABLE original key (replace = overwrite). */
  public LetterheadUpload beginUpload(
      IhrmsPrincipal.User actor, String companyId, LetterheadUploadRequest req, String ip) {
    requireCompany(companyId);
    if (req == null || req.sizeBytes() <= 0 || req.sizeBytes() > MAX_BYTES) {
      throw badRequest("The letterhead must be a non-empty file of 10 MB or smaller.");
    }
    String type = normalizeUploadType(req.contentType());
    if (isWord(type) && !converter.isAvailable()) {
      throw badRequest(
          "Word conversion isn't available on this server. Please export your letterhead to PDF and upload the PDF.");
    }
    String url = storage.presignedPutUrl(originalKey(companyId), type, UPLOAD_TTL);
    return new LetterheadUpload(url, "PUT", Map.of("Content-Type", type), UPLOAD_TTL);
  }

  /**
   * Read the uploaded object; convert Word→PDF if needed; normalize to the first page; validate the page size;
   * rasterize a preview; store the normalized PDF + preview; seed a default margin box; stamp the row.
   */
  @Transactional
  public LetterheadView confirmUpload(IhrmsPrincipal.User actor, String companyId, String ip) {
    Company c = requireCompany(companyId);

    byte[] original;
    try {
      original = storage.getObjectBytes(originalKey(companyId));
    } catch (RuntimeException e) {
      throw badRequest("We couldn't read the uploaded file — please try the upload again.");
    }
    if (original == null || original.length == 0) {
      throw badRequest("We couldn't find the uploaded file — please try the upload again.");
    }
    if (original.length > MAX_BYTES) {
      storage.delete(originalKey(companyId));
      throw badRequest("The letterhead must be 10 MB or smaller.");
    }

    String sniffed = sniffType(original);
    byte[] pdfBytes;
    String originalType;
    if (PDF_TYPE.equals(sniffed)) {
      pdfBytes = original;
      originalType = PDF_TYPE;
    } else if (DOCX_TYPE.equals(sniffed) || DOC_TYPE.equals(sniffed)) {
      originalType = sniffed;
      try {
        pdfBytes = converter.wordToPdf(original, DOCX_TYPE.equals(sniffed) ? "docx" : "doc");
      } catch (DocumentConverter.ConversionUnavailableException e) {
        throw badRequest(e.getMessage());
      } catch (DocumentConverter.ConversionFailedException e) {
        throw badRequest(e.getMessage());
      }
    } else {
      storage.delete(originalKey(companyId));
      throw badRequest("Upload a PDF or Word (.docx/.doc) letterhead.");
    }

    byte[] normalized;
    PdfLetterheadOps.PageSize size;
    byte[] preview;
    try {
      normalized = PdfLetterheadOps.firstPageOnly(pdfBytes); // first page used if multi-page
      size = PdfLetterheadOps.pageSize(normalized);
      preview = PdfLetterheadOps.renderPreviewPng(normalized, PREVIEW_DPI);
    } catch (Exception e) {
      log.warn("Letterhead PDF unreadable for {}: {}", companyId, e.toString());
      throw badRequest("We couldn't read that letterhead as a PDF page — please try a different file.");
    }
    if (size.widthPt() < MIN_PAGE_PT
        || size.heightPt() < MIN_PAGE_PT
        || size.widthPt() > MAX_PAGE_PT
        || size.heightPt() > MAX_PAGE_PT) {
      storage.delete(originalKey(companyId));
      throw badRequest("That page size looks unusual — use a standard A4 or Letter letterhead.");
    }

    storage.putObject(pdfKey(companyId), normalized, PDF_TYPE);
    storage.putObject(previewKey(companyId), preview, "image/png");

    c.setLetterheadOriginalKey(originalKey(companyId));
    c.setLetterheadOriginalType(originalType);
    c.setLetterheadPdfKey(pdfKey(companyId));
    c.setLetterheadPreviewKey(previewKey(companyId));
    c.setLetterheadPageWidthPt(size.widthPt());
    c.setLetterheadPageHeightPt(size.heightPt());
    // Fresh artwork → seed a fresh default margin box (an old box could sit over new artwork).
    c.setLetterheadMarginTopPt(round(size.heightPt() * DEFAULT_TOP_FRACTION));
    c.setLetterheadMarginBottomPt(round(size.heightPt() * DEFAULT_BOTTOM_FRACTION));
    c.setLetterheadMarginLeftPt(round(SIDE_DEFAULT_PT));
    c.setLetterheadMarginRightPt(round(SIDE_DEFAULT_PT));
    c.setLetterheadUpdatedAt(Instant.now());
    c.setLetterheadUpdatedByUserId(actor.userId());
    companies.save(c);

    audit.record(
        AuditActor.from(actor),
        "LETTERHEAD_UPDATED",
        "Company",
        companyId,
        // NB: int (not long) — a Long in JSON metadata deserializes back to Integer in Hibernate's dirty-check
        // snapshot, so Long != Integer flags the append-only audit row dirty → an UPDATE the trigger rejects.
        Map.of(
            "action", "uploaded",
            "originalType", originalType,
            "pageWidthPt", (int) Math.round(size.widthPt()),
            "pageHeightPt", (int) Math.round(size.heightPt())),
        ip);
    return view(c);
  }

  /** Save the content margin box (points). Validated against the page so content always has room. */
  @Transactional
  public LetterheadView saveMargins(
      IhrmsPrincipal.User actor, String companyId, MarginsRequest req, String ip) {
    Company c = requireCompany(companyId);
    if (c.getLetterheadPdfKey() == null) {
      throw badRequest("Upload a letterhead before setting its margins.");
    }
    double pageW = orDefault(c.getLetterheadPageWidthPt(), 595.276);
    double pageH = orDefault(c.getLetterheadPageHeightPt(), 841.890);
    double top = require(req == null ? null : req.topPt(), "top");
    double bottom = require(req == null ? null : req.bottomPt(), "bottom");
    double left = require(req == null ? null : req.leftPt(), "left");
    double right = require(req == null ? null : req.rightPt(), "right");
    if (top < 0 || bottom < 0 || left < 0 || right < 0) {
      throw badRequest("Margins can't be negative.");
    }
    if (pageH - top - bottom < MIN_CONTENT_PT || pageW - left - right < MIN_CONTENT_PT) {
      throw badRequest("Those margins leave too little room for content — pull the guides apart.");
    }
    c.setLetterheadMarginTopPt(round(top));
    c.setLetterheadMarginBottomPt(round(bottom));
    c.setLetterheadMarginLeftPt(round(left));
    c.setLetterheadMarginRightPt(round(right));
    c.setLetterheadUpdatedAt(Instant.now());
    c.setLetterheadUpdatedByUserId(actor.userId());
    companies.save(c);
    audit.record(
        AuditActor.from(actor),
        "LETTERHEAD_UPDATED",
        "Company",
        companyId,
        Map.of("action", "margins", "topPt", (int) Math.round(top), "bottomPt", (int) Math.round(bottom),
            "leftPt", (int) Math.round(left), "rightPt", (int) Math.round(right)),
        ip);
    return view(c);
  }

  /** Reset the margin box to the sensible defaults (25% top / 15% bottom / 1.7cm sides) for this page. */
  @Transactional
  public LetterheadView resetMargins(IhrmsPrincipal.User actor, String companyId, String ip) {
    Company c = requireCompany(companyId);
    if (c.getLetterheadPdfKey() == null) {
      throw badRequest("Upload a letterhead before setting its margins.");
    }
    double pageH = orDefault(c.getLetterheadPageHeightPt(), 841.890);
    c.setLetterheadMarginTopPt(round(pageH * DEFAULT_TOP_FRACTION));
    c.setLetterheadMarginBottomPt(round(pageH * DEFAULT_BOTTOM_FRACTION));
    c.setLetterheadMarginLeftPt(round(SIDE_DEFAULT_PT));
    c.setLetterheadMarginRightPt(round(SIDE_DEFAULT_PT));
    c.setLetterheadUpdatedAt(Instant.now());
    c.setLetterheadUpdatedByUserId(actor.userId());
    companies.save(c);
    audit.record(
        AuditActor.from(actor), "LETTERHEAD_UPDATED", "Company", companyId, Map.of("action", "margins-reset"), ip);
    return view(c);
  }

  /** Remove the letterhead: delete the objects (best-effort) + clear the row. Documents render plain again. */
  @Transactional
  public LetterheadView remove(IhrmsPrincipal.User actor, String companyId, String ip) {
    Company c = requireCompany(companyId);
    for (String key : new String[] {c.getLetterheadPdfKey(), c.getLetterheadOriginalKey(), c.getLetterheadPreviewKey()}) {
      if (key != null) {
        try {
          storage.delete(key);
        } catch (RuntimeException e) {
          log.warn("Letterhead object delete failed ({}): {}", key, e.toString());
        }
      }
    }
    c.setLetterheadPdfKey(null);
    c.setLetterheadOriginalKey(null);
    c.setLetterheadOriginalType(null);
    c.setLetterheadPreviewKey(null);
    c.setLetterheadPageWidthPt(null);
    c.setLetterheadPageHeightPt(null);
    c.setLetterheadMarginTopPt(null);
    c.setLetterheadMarginBottomPt(null);
    c.setLetterheadMarginLeftPt(null);
    c.setLetterheadMarginRightPt(null);
    c.setLetterheadUpdatedAt(Instant.now());
    c.setLetterheadUpdatedByUserId(actor.userId());
    companies.save(c);
    audit.record(
        AuditActor.from(actor), "LETTERHEAD_UPDATED", "Company", companyId, Map.of("action", "removed"), ip);
    return view(c);
  }

  /** Current letterhead metadata + a short-lived presigned preview of the first page. */
  @Transactional(readOnly = true)
  public LetterheadView get(String companyId) {
    return view(requireCompany(companyId));
  }

  private LetterheadView view(Company c) {
    boolean present = c.getLetterheadPdfKey() != null;
    String previewUrl = null;
    if (present && c.getLetterheadPreviewKey() != null) {
      try {
        previewUrl = storage.presignedGetUrl(c.getLetterheadPreviewKey(), PREVIEW_TTL);
      } catch (RuntimeException e) {
        log.warn("Letterhead preview presign failed ({}): {}", c.getLetterheadPreviewKey(), e.toString());
      }
    }
    return new LetterheadView(
        present,
        c.getLetterheadPageWidthPt(),
        c.getLetterheadPageHeightPt(),
        c.getLetterheadMarginTopPt(),
        c.getLetterheadMarginBottomPt(),
        c.getLetterheadMarginLeftPt(),
        c.getLetterheadMarginRightPt(),
        c.getLetterheadOriginalType(),
        previewUrl,
        c.getLetterheadUpdatedAt() == null ? null : c.getLetterheadUpdatedAt().toString(),
        converter.isAvailable());
  }

  // --- Render-time resolution ------------------------------------------------

  /**
   * Render {@code template} with the company's letterhead applied to {@code model}: content is rendered at the
   * letterhead's page size within the saved margins, then the letterhead is stamped behind every page. Any
   * failure DEGRADES to the plain layout and still returns a PDF (§3.5: branding must never fail a generation).
   */
  public byte[] renderBranded(String template, Map<String, Object> model, String companyId) {
    Resolved r = resolve(companyId);
    if (r == null) {
      applyPlain(model);
      return html.render(template, model);
    }
    try {
      model.put("pageCss", brandedCss(r));
      model.put("letterheadHeader", null);
      model.put("letterheadFooter", null);
      byte[] content = html.render(template, model);
      return PdfLetterheadOps.overlayBackground(content, r.pdf);
    } catch (Exception e) {
      log.warn(
          "Branded PDF render failed (company {}, template {}) — retrying plain: {}",
          companyId,
          template,
          e.toString());
      applyPlain(model);
      return html.render(template, model);
    }
  }

  private static void applyPlain(Map<String, Object> model) {
    model.put("pageCss", PLAIN_PAGE_CSS);
    model.put("letterheadHeader", null);
    model.put("letterheadFooter", null);
  }

  /** Load the letterhead PDF + geometry, or null when unset / unreadable (→ the caller renders plain). */
  private Resolved resolve(String companyId) {
    try {
      Company c = companies.findById(companyId).orElse(null);
      if (c == null || c.getLetterheadPdfKey() == null) {
        return null;
      }
      byte[] pdf = storage.getObjectBytes(c.getLetterheadPdfKey());
      if (pdf == null || pdf.length == 0) {
        return null;
      }
      double pageW = orDefault(c.getLetterheadPageWidthPt(), 595.276);
      double pageH = orDefault(c.getLetterheadPageHeightPt(), 841.890);
      return new Resolved(
          pdf,
          pageW,
          pageH,
          orDefault(c.getLetterheadMarginTopPt(), pageH * DEFAULT_TOP_FRACTION),
          orDefault(c.getLetterheadMarginBottomPt(), pageH * DEFAULT_BOTTOM_FRACTION),
          orDefault(c.getLetterheadMarginLeftPt(), SIDE_DEFAULT_PT),
          orDefault(c.getLetterheadMarginRightPt(), SIDE_DEFAULT_PT));
    } catch (RuntimeException e) {
      log.warn("Letterhead resolve failed for {} — rendering plain: {}", companyId, e.toString());
      return null;
    }
  }

  /**
   * The branded page CSS: an A-size page matching the letterhead, the saved margin box, transparent background
   * so the overlay shows through, and the running header/footer pulled from flow but NOT placed in any margin
   * box → both the header band and the plain "CONFIDENTIAL" footer are suppressed (the letterhead provides all
   * branding). No template-body edit is needed.
   */
  private static String brandedCss(Resolved r) {
    return "@page{size:"
        + pt(r.pageWidthPt)
        + " "
        + pt(r.pageHeightPt)
        + ";margin:"
        + pt(r.marginTopPt)
        + " "
        + pt(r.marginRightPt)
        + " "
        + pt(r.marginBottomPt)
        + " "
        + pt(r.marginLeftPt)
        + "}html,body{background:transparent}"
        + "#letterhead{position:running(letterhead)}#footer{position:running(footer)}";
  }

  private static String pt(double v) {
    return String.format(Locale.ROOT, "%.3fpt", v);
  }

  // --- helpers ---------------------------------------------------------------

  private Company requireCompany(String companyId) {
    return companies
        .findById(companyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
  }

  private static String normalizeUploadType(String contentType) {
    String t = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    if (t.equals(PDF_TYPE)) {
      return PDF_TYPE;
    }
    if (t.equals(DOCX_TYPE)) {
      return DOCX_TYPE;
    }
    if (t.equals(DOC_TYPE)) {
      return DOC_TYPE;
    }
    throw badRequest("Upload a PDF or Word (.docx/.doc) letterhead.");
  }

  private static boolean isWord(String type) {
    return DOCX_TYPE.equals(type) || DOC_TYPE.equals(type);
  }

  /** Sniff the real type from magic bytes so a mislabelled upload can't slip past (PDF / OOXML zip / OLE2). */
  private static String sniffType(byte[] b) {
    if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
      return PDF_TYPE;
    }
    if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 0x03 && b[3] == 0x04) {
      return DOCX_TYPE; // OOXML (docx) is a zip
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0xD0
        && (b[1] & 0xFF) == 0xCF
        && (b[2] & 0xFF) == 0x11
        && (b[3] & 0xFF) == 0xE0) {
      return DOC_TYPE; // legacy OLE2 (.doc)
    }
    return "application/octet-stream";
  }

  private static double require(Double v, String name) {
    if (v == null || v.isNaN() || v.isInfinite()) {
      throw badRequest("Missing " + name + " margin.");
    }
    return v;
  }

  private static double orDefault(Double v, double fallback) {
    return v == null ? fallback : v;
  }

  private static double round(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record Resolved(
      byte[] pdf,
      double pageWidthPt,
      double pageHeightPt,
      double marginTopPt,
      double marginBottomPt,
      double marginLeftPt,
      double marginRightPt) {}
}
