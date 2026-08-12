package com.ihrms.companies;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadPartView;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadUpload;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadUploadRequest;
import com.ihrms.companies.dto.LetterheadDtos.LetterheadView;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.onboarding.HtmlPdfRenderer;
import com.ihrms.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-company letterhead (§3.5): SUPER_ADMIN uploads a HEADER and/or FOOTER band image (one per company per
 * part, replaceable); every PIPELINE document (agreements, offboarding docs, clearance, relieving/experience
 * letters, offer letter — Forms 1-4 EXCLUDED) generated FROM THEN ON renders on it. Already-generated PDFs are
 * never re-rendered (stored artifacts are immutable).
 *
 * <p>Two responsibilities: (1) the SUPER_ADMIN upload handshake — presigned PUT (begin) → validate+decode
 * (confirm) → the company row carries the stable key + pixel dims; (2) the render-time resolver — {@link
 * #renderBranded} embeds the images into the {@code agreement}/{@code offboarding-clearance} model and derives
 * the {@code @page} band heights from each image's aspect ratio so body text NEVER overlaps a band, on page 1 or
 * any continuation page. A missing/unreadable/undecodable object DEGRADES GRACEFULLY to the plain layout — a
 * document is never failed because branding is missing.
 */
@Service
public class LetterheadService {

  private static final Logger log = LoggerFactory.getLogger(LetterheadService.class);

  static final int MAX_BYTES = 5 * 1024 * 1024; // 5 MB
  static final int MIN_WIDTH_PX = 1000; // print-quality floor across the A4 width
  static final int UPLOAD_TTL = 300; // 5 min presigned PUT
  static final int PREVIEW_TTL = 300; // 5 min presigned GET

  // Page geometry (A4). A part present ⇒ full-bleed bands (side margins 0, top-center/bottom-center span the
  // full page width) with the body inset by padding; band height = page width × image aspect + a safety gap.
  private static final double PAGE_W_CM = 21.0;
  private static final double SIDE_INSET_CM = 1.7;
  private static final double SAFETY_CM = 0.15;
  private static final double DEFAULT_TOP_CM = 2.7;
  private static final double DEFAULT_BOTTOM_CM = 2.1;

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

  public LetterheadService(
      CompanyRepository companies, StorageService storage, AuditService audit, HtmlPdfRenderer html) {
    this.companies = companies;
    this.storage = storage;
    this.audit = audit;
    this.html = html;
  }

  enum Part {
    HEADER,
    FOOTER
  }

  // --- SUPER_ADMIN upload handshake ------------------------------------------

  /** Validate the declared type/size, then presign a PUT to the STABLE per-part key (replace = overwrite). */
  public LetterheadUpload beginUpload(
      IhrmsPrincipal.User actor, String companyId, String partRaw, LetterheadUploadRequest req, String ip) {
    requireCompany(companyId);
    Part part = parsePart(partRaw);
    String type = normalizeType(req == null ? null : req.contentType());
    if (req == null || req.sizeBytes() <= 0 || req.sizeBytes() > MAX_BYTES) {
      throw badRequest("Image must be a non-empty file of 5 MB or smaller.");
    }
    String key = storage.buildLetterheadKey(companyId, part.name());
    String url = storage.presignedPutUrl(key, type, UPLOAD_TTL);
    return new LetterheadUpload(url, "PUT", Map.of("Content-Type", type), UPLOAD_TTL, part.name());
  }

  /** Read the uploaded object, decode+validate it (real PNG/JPG, ≥ min width, ≤ max bytes), stamp the row. */
  @Transactional
  public LetterheadView confirmUpload(
      IhrmsPrincipal.User actor, String companyId, String partRaw, String ip) {
    Company c = requireCompany(companyId);
    Part part = parsePart(partRaw);
    String key = storage.buildLetterheadKey(companyId, part.name());

    byte[] bytes;
    try {
      bytes = storage.getObjectBytes(key);
    } catch (RuntimeException e) {
      throw badRequest("We couldn't read the uploaded image — please try the upload again.");
    }
    if (bytes == null || bytes.length == 0) {
      throw badRequest("We couldn't find the uploaded image — please try the upload again.");
    }
    if (bytes.length > MAX_BYTES) {
      storage.delete(key);
      throw badRequest("Image must be 5 MB or smaller.");
    }
    ImageInfo img = decode(bytes);
    if (img == null) {
      storage.delete(key);
      throw badRequest("Upload a PNG or JPG image (PDFs aren't supported).");
    }
    if (img.width < MIN_WIDTH_PX) {
      storage.delete(key);
      throw badRequest("Image must be at least " + MIN_WIDTH_PX + "px wide for print quality.");
    }

    if (part == Part.HEADER) {
      c.setLetterheadHeaderKey(key);
      c.setLetterheadHeaderType(img.contentType);
      c.setLetterheadHeaderWidth(img.width);
      c.setLetterheadHeaderHeight(img.height);
    } else {
      c.setLetterheadFooterKey(key);
      c.setLetterheadFooterType(img.contentType);
      c.setLetterheadFooterWidth(img.width);
      c.setLetterheadFooterHeight(img.height);
    }
    c.setLetterheadUpdatedAt(Instant.now());
    c.setLetterheadUpdatedByUserId(actor.userId());
    companies.save(c);
    audit.record(
        AuditActor.from(actor),
        "LETTERHEAD_UPDATED",
        "Company",
        companyId,
        Map.of("part", part.name(), "width", img.width, "height", img.height),
        ip);
    return view(c);
  }

  /** Revert a part to plain: delete the stored object (best-effort) + clear its columns. Audited. */
  @Transactional
  public LetterheadView remove(IhrmsPrincipal.User actor, String companyId, String partRaw, String ip) {
    Company c = requireCompany(companyId);
    Part part = parsePart(partRaw);
    String key = part == Part.HEADER ? c.getLetterheadHeaderKey() : c.getLetterheadFooterKey();
    if (key != null) {
      try {
        storage.delete(key);
      } catch (RuntimeException e) {
        log.warn("Letterhead object delete failed ({}): {}", key, e.toString());
      }
    }
    if (part == Part.HEADER) {
      c.setLetterheadHeaderKey(null);
      c.setLetterheadHeaderType(null);
      c.setLetterheadHeaderWidth(null);
      c.setLetterheadHeaderHeight(null);
    } else {
      c.setLetterheadFooterKey(null);
      c.setLetterheadFooterType(null);
      c.setLetterheadFooterWidth(null);
      c.setLetterheadFooterHeight(null);
    }
    c.setLetterheadUpdatedAt(Instant.now());
    c.setLetterheadUpdatedByUserId(actor.userId());
    companies.save(c);
    audit.record(
        AuditActor.from(actor),
        "LETTERHEAD_UPDATED",
        "Company",
        companyId,
        Map.of("part", part.name(), "removed", true),
        ip);
    return view(c);
  }

  /** Current letterhead metadata + short-lived presigned preview URLs. */
  @Transactional(readOnly = true)
  public LetterheadView get(String companyId) {
    return view(requireCompany(companyId));
  }

  private LetterheadView view(Company c) {
    return new LetterheadView(
        partView(c.getLetterheadHeaderKey(), c.getLetterheadHeaderType(), c.getLetterheadHeaderWidth(), c.getLetterheadHeaderHeight()),
        partView(c.getLetterheadFooterKey(), c.getLetterheadFooterType(), c.getLetterheadFooterWidth(), c.getLetterheadFooterHeight()),
        c.getLetterheadUpdatedAt() == null ? null : c.getLetterheadUpdatedAt().toString());
  }

  private LetterheadPartView partView(String key, String type, Integer w, Integer h) {
    if (key == null || w == null || h == null) {
      return null;
    }
    String preview = null;
    try {
      preview = storage.presignedGetUrl(key, PREVIEW_TTL);
    } catch (RuntimeException e) {
      log.warn("Letterhead preview presign failed ({}): {}", key, e.toString());
    }
    return new LetterheadPartView(w, h, type, preview);
  }

  // --- Render-time resolution ------------------------------------------------

  /**
   * Render {@code template} ({@code agreement} / {@code offboarding-clearance}) with the company's letterhead
   * applied to {@code model}. Any failure — resolving, embedding, or even openhtmltopdf choking on the image —
   * DEGRADES to the plain layout and still returns a PDF (§3.5: branding must never fail a generation).
   */
  public byte[] renderBranded(String template, Map<String, Object> model, String companyId) {
    applyTo(model, companyId);
    try {
      return html.render(template, model);
    } catch (RuntimeException e) {
      log.warn(
          "Branded PDF render failed (company {}, template {}) — retrying plain: {}",
          companyId,
          template,
          e.toString());
      applyPlain(model);
      return html.render(template, model);
    }
  }

  /** Populate {@code pageCss} + optional {@code letterheadHeader}/{@code letterheadFooter} data URIs. */
  public void applyTo(Map<String, Object> model, String companyId) {
    Resolved r = null;
    try {
      r = resolve(companyId);
    } catch (RuntimeException e) {
      log.warn("Letterhead resolve failed for {} — rendering plain: {}", companyId, e.toString());
    }
    if (r == null) {
      applyPlain(model);
      return;
    }
    model.put("pageCss", brandedCss(r));
    model.put("letterheadHeader", r.header == null ? null : r.header.dataUri);
    model.put("letterheadFooter", r.footer == null ? null : r.footer.dataUri);
  }

  private static void applyPlain(Map<String, Object> model) {
    model.put("pageCss", PLAIN_PAGE_CSS);
    model.put("letterheadHeader", null);
    model.put("letterheadFooter", null);
  }

  private Resolved resolve(String companyId) {
    Company c = companies.findById(companyId).orElse(null);
    if (c == null) {
      return null;
    }
    ResolvedPart header =
        loadPart(c.getLetterheadHeaderKey(), c.getLetterheadHeaderType(), c.getLetterheadHeaderWidth(), c.getLetterheadHeaderHeight());
    ResolvedPart footer =
        loadPart(c.getLetterheadFooterKey(), c.getLetterheadFooterType(), c.getLetterheadFooterWidth(), c.getLetterheadFooterHeight());
    return (header == null && footer == null) ? null : new Resolved(header, footer);
  }

  private ResolvedPart loadPart(String key, String type, Integer w, Integer h) {
    if (key == null || w == null || h == null || w <= 0 || h <= 0) {
      return null;
    }
    try {
      byte[] bytes = storage.getObjectBytes(key);
      if (bytes == null || bytes.length == 0) {
        return null;
      }
      String uri =
          "data:" + (type == null ? "image/png" : type) + ";base64," + Base64.getEncoder().encodeToString(bytes);
      return new ResolvedPart(uri, w, h);
    } catch (RuntimeException e) {
      log.warn("Letterhead object unreadable ({}) — that band renders plain: {}", key, e.toString());
      return null;
    }
  }

  /** Build the deterministic @page + band CSS. Body text sits between the top/bottom margins (= band heights). */
  private String brandedCss(Resolved r) {
    double topCm =
        r.header == null ? DEFAULT_TOP_CM : PAGE_W_CM * r.header.height / (double) r.header.width + SAFETY_CM;
    double botCm =
        r.footer == null ? DEFAULT_BOTTOM_CM : PAGE_W_CM * r.footer.height / (double) r.footer.width + SAFETY_CM;

    StringBuilder sb = new StringBuilder();
    sb.append("@page{size:A4;margin:")
        .append(cm(topCm))
        .append(" 0cm ")
        .append(cm(botCm))
        .append(" 0cm;@top-center{content:element(letterhead)}@bottom-center{content:element(footer)}}");
    // Full-bleed bands ⇒ side margins are 0; inset the body instead so text keeps its 1.7cm side gutter.
    sb.append("body{padding-left:").append(cm(SIDE_INSET_CM)).append(";padding-right:").append(cm(SIDE_INSET_CM)).append("}");

    if (r.header == null) {
      sb.append("#letterhead{position:running(letterhead);height:1.7cm;width:100%}");
    } else {
      double h = PAGE_W_CM * r.header.height / (double) r.header.width;
      sb.append("#letterhead{position:running(letterhead);width:100%;height:").append(cm(h)).append("}");
      sb.append("#letterhead img{width:100%;height:auto;display:block}");
    }
    if (r.footer == null) {
      sb.append(
          "#footer{position:running(footer);width:100%;text-align:center;font-size:7.5px;color:#999;"
              + "border-top:0.5px solid #ddd;padding-top:3px}");
    } else {
      double h = PAGE_W_CM * r.footer.height / (double) r.footer.width;
      sb.append("#footer{position:running(footer);width:100%;height:").append(cm(h)).append("}");
      sb.append("#footer img{width:100%;height:auto;display:block}");
    }
    return sb.toString();
  }

  private static String cm(double v) {
    return String.format(Locale.ROOT, "%.3fcm", v);
  }

  // --- helpers ---------------------------------------------------------------

  private Company requireCompany(String companyId) {
    return companies
        .findById(companyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
  }

  private static Part parsePart(String raw) {
    if (raw != null) {
      try {
        return Part.valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    throw badRequest("Part must be HEADER or FOOTER.");
  }

  private static String normalizeType(String contentType) {
    String t = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    if (t.equals("image/png") || t.equals("image/jpeg")) {
      return t;
    }
    if (t.equals("image/jpg")) {
      return "image/jpeg";
    }
    throw badRequest("Upload a PNG or JPG image (PDFs aren't supported).");
  }

  /** Decode with ImageIO — proves it's a real PNG/JPEG and yields the true pixel dimensions + type. */
  private static ImageInfo decode(byte[] bytes) {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (iis == null) {
        return null;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) {
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(iis);
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        String fmt = reader.getFormatName();
        String type =
            fmt == null
                ? null
                : fmt.equalsIgnoreCase("png")
                    ? "image/png"
                    : (fmt.equalsIgnoreCase("jpeg") || fmt.equalsIgnoreCase("jpg")) ? "image/jpeg" : null;
        if (type == null || w <= 0 || h <= 0) {
          return null;
        }
        return new ImageInfo(w, h, type);
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record ImageInfo(int width, int height, String contentType) {}

  private record ResolvedPart(String dataUri, int width, int height) {}

  private record Resolved(ResolvedPart header, ResolvedPart footer) {}
}
