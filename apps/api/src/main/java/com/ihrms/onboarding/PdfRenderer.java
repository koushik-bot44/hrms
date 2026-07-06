package com.ihrms.onboarding;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Typeset renderer for the Form 4 documents manifest (OpenPDF). Forms 1/2/3 are rendered from their
 * faithful HTML templates by {@link HtmlPdfRenderer}; the five PDFs are merged there via PDFBox. The
 * manifest is branded with the joining company name and prints the system Employee ID only when
 * present (blank pre-approval).
 */
final class PdfRenderer {

  private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
  private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
  private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
  private static final Font VALUE = FontFactory.getFont(FontFactory.HELVETICA, 9);
  private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.NORMAL, Color.DARK_GRAY);
  private static final Color GRID_HEAD = new Color(238, 238, 238);
  private static final Color RULE = new Color(200, 200, 200);

  private PdfRenderer() {}

  // --- Form 4 manifest ------------------------------------------------------

  static byte[] form4Manifest(
      String companyName, String employeeCode, List<com.ihrms.domain.model.Document> documents) {
    Document doc = open();
    ByteArrayOutputStream out = writer(doc);
    header(doc, companyName, "Form 4 — Documents", employeeCode);
    if (documents == null || documents.isEmpty()) {
      add(doc, new Paragraph("No documents uploaded.", VALUE));
      return close(doc, out);
    }
    PdfPTable t = grid(new String[] {"Document", "Group", "File", "Status"}, new float[] {30, 12, 40, 18});
    for (com.ihrms.domain.model.Document d : documents) {
      cell(t, d.getDocType().name());
      cell(t, d.getGroupIndex() == null ? "—" : String.valueOf(d.getGroupIndex()));
      cell(t, d.getFileName());
      cell(t, d.getStatus().name());
    }
    add(doc, t);
    return close(doc, out);
  }

  // --- primitives -----------------------------------------------------------

  private static Document open() {
    return new Document(PageSize.A4, 42, 42, 52, 42);
  }

  private static ByteArrayOutputStream writer(Document doc) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfWriter.getInstance(doc, out);
    doc.open();
    return out;
  }

  private static byte[] close(Document doc, ByteArrayOutputStream out) {
    if (doc.isOpen()) {
      doc.close();
    }
    return out.toByteArray();
  }

  private static void header(Document doc, String companyName, String title, String employeeCode) {
    Paragraph brand = new Paragraph(companyName == null || companyName.isBlank() ? "Company" : companyName, TITLE);
    brand.setAlignment(Element.ALIGN_CENTER);
    add(doc, brand);
    Paragraph t = new Paragraph(title, HEADING);
    t.setAlignment(Element.ALIGN_CENTER);
    t.setSpacingAfter(4);
    add(doc, t);
    Paragraph id =
        new Paragraph(
            "Employee ID: " + (employeeCode == null ? "" : employeeCode), SMALL);
    id.setAlignment(Element.ALIGN_RIGHT);
    id.setSpacingAfter(6);
    add(doc, id);
    add(doc, rule());
  }

  private static PdfPTable grid(String[] headers, float[] widths) {
    PdfPTable t = new PdfPTable(headers.length);
    t.setWidthPercentage(100);
    setWidths(t, widths);
    t.setSpacingBefore(4);
    for (String h : headers) {
      PdfPCell c = new PdfPCell(new Phrase(h, LABEL));
      c.setBackgroundColor(GRID_HEAD);
      c.setPadding(3);
      t.addCell(c);
    }
    return t;
  }

  private static void cell(PdfPTable t, String value) {
    PdfPCell c = new PdfPCell(new Phrase(value == null ? "" : value, VALUE));
    c.setPadding(3);
    t.addCell(c);
  }

  private static PdfPTable rule() {
    PdfPTable t = new PdfPTable(1);
    t.setWidthPercentage(100);
    PdfPCell c = new PdfPCell();
    c.setBorder(Rectangle.BOTTOM);
    c.setBorderColor(RULE);
    c.setFixedHeight(3f);
    t.addCell(c);
    return t;
  }

  private static void setWidths(PdfPTable t, float[] widths) {
    try {
      t.setWidths(widths);
    } catch (DocumentException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void add(Document doc, Element element) {
    try {
      doc.add(element);
    } catch (DocumentException e) {
      throw new IllegalStateException("PDF generation failed", e);
    }
  }
}
