package com.ihrms.companies;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import javax.imageio.ImageIO;
import org.apache.pdfbox.multipdf.Overlay;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * The PDFBox operations behind the letterhead document model (§3.5), all on PDFBox 2.0.x (already on the
 * classpath via openhtmltopdf-pdfbox). Pure, stateless helpers:
 *
 * <ul>
 *   <li>{@link #firstPageOnly} — normalize an uploaded PDF to a single-page template (the first page).
 *   <li>{@link #pageSize} — the first page's media box in points, so content renders at the SAME size and the
 *       overlay aligns 1:1.
 *   <li>{@link #renderPreviewPng} — rasterize the first page for the margin editor's live preview.
 *   <li>{@link #overlayBackground} — stamp the single-page letterhead BEHIND every page of the content PDF
 *       (content stays on top, never scaled or clipped; overflow pages each carry the letterhead).
 * </ul>
 */
final class PdfLetterheadOps {

  private PdfLetterheadOps() {}

  record PageSize(double widthPt, double heightPt) {}

  /** The first page's size in points (1/72in). */
  static PageSize pageSize(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("PDF has no pages");
      }
      PDRectangle box = doc.getPage(0).getMediaBox();
      return new PageSize(box.getWidth(), box.getHeight());
    }
  }

  /** Number of pages (used to report first-page-used when the upload has more than one). */
  static int pageCount(byte[] pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return doc.getNumberOfPages();
    }
  }

  /**
   * Return a single-page PDF containing only the first page. If the upload is already one page, the bytes pass
   * through unchanged; otherwise the first page is imported into a fresh document (deep-copied).
   */
  static byte[] firstPageOnly(byte[] pdf) throws IOException {
    try (PDDocument src = PDDocument.load(pdf)) {
      if (src.getNumberOfPages() == 0) {
        throw new IOException("PDF has no pages");
      }
      if (src.getNumberOfPages() == 1) {
        return pdf;
      }
      try (PDDocument dst = new PDDocument()) {
        PDPage imported = dst.importPage(src.getPage(0)); // deep-copies content + resources
        imported.setMediaBox(src.getPage(0).getMediaBox());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dst.save(out);
        return out.toByteArray();
      }
    }
  }

  /** Rasterize the first page to a PNG at {@code dpi} for the editor preview. */
  static byte[] renderPreviewPng(byte[] pdf, float dpi) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("PDF has no pages");
      }
      PDFRenderer renderer = new PDFRenderer(doc);
      var img = renderer.renderImageWithDPI(0, dpi, ImageType.RGB);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(img, "png", out);
      return out.toByteArray();
    }
  }

  /**
   * Stamp {@code letterheadPdf} (a single page) as the BACKGROUND of EVERY page of {@code contentPdf}. The
   * content is drawn on top, never scaled or clipped; overflow pages each get the letterhead. Requires the two
   * page sizes to match (the caller renders content at {@link #pageSize}) so the artwork aligns 1:1.
   */
  static byte[] overlayBackground(byte[] contentPdf, byte[] letterheadPdf) throws IOException {
    try (PDDocument content = PDDocument.load(contentPdf);
        PDDocument letterhead = PDDocument.load(letterheadPdf)) {
      Overlay overlay = new Overlay();
      try {
        overlay.setInputPDF(content);
        overlay.setAllPagesOverlayPDF(letterhead);
        overlay.setOverlayPosition(Overlay.Position.BACKGROUND);
        overlay.overlay(new HashMap<>()); // empty map → the all-pages overlay applies to every page
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        content.save(out);
        return out.toByteArray();
      } finally {
        overlay.close();
      }
    }
  }
}
