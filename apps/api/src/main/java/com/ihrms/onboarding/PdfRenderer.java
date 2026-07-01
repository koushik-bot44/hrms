package com.ihrms.onboarding;

import com.ihrms.onboarding.dto.OnboardingDtos.CharacterReference;
import com.ihrms.onboarding.dto.OnboardingDtos.EducationalQualification;
import com.ihrms.onboarding.dto.OnboardingDtos.FamilyDetail;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.onboarding.dto.OnboardingDtos.WorkingExperience;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Typeset renderer for the onboarding PDFs (OpenPDF). Each form is branded with the joining company
 * name; the system Employee ID is printed only when present (blank pre-approval); the captured
 * signature image is stamped into Forms 1 & 2. Kept separate from {@link PdfService} (which owns
 * data-loading + storage) so the layout is a pure function of the view data.
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

  // --- Form 1 ---------------------------------------------------------------

  static byte[] form1(String companyName, String employeeCode, Form1View v, byte[] signatureImage) {
    Document doc = open();
    ByteArrayOutputStream out = writer(doc);
    header(doc, companyName, "Form 1 — Personal Details", employeeCode);
    if (v == null) {
      add(doc, new Paragraph("This form was not completed.", VALUE));
      return close(doc, out);
    }
    PdfPTable kv = kvTable();
    kv(kv, "Name", v.name());
    kv(kv, "Date of Birth", v.dateOfBirth());
    kv(kv, "Email", v.email());
    kv(kv, "Mobile", v.mobile());
    kv(kv, "Designation", v.designation());
    kv(kv, "Offered CTC", v.offeredCtc());
    kv(kv, "Marital Status", v.maritalStatus());
    kv(kv, "Blood Group", v.bloodGroup());
    kv(kv, "City", v.city());
    kv(kv, "Current Address", v.currentAddress());
    kv(kv, "Permanent Address", v.permanentAddress());
    kv(kv, "Closest Relative", v.closestRelativeName());
    kv(kv, "Relative Phone", v.closestRelativePhone());
    kv(kv, "Relationship", v.relationship());
    add(doc, kv);

    List<EducationalQualification> edu = v.educationalQualifications();
    if (edu != null && !edu.isEmpty()) {
      section(doc, "Educational Qualifications");
      PdfPTable t = grid(new String[] {"Qualification", "University", "Year", "%"}, new float[] {34, 34, 16, 16});
      for (EducationalQualification e : edu) {
        cell(t, e.qualification());
        cell(t, e.university());
        cell(t, e.yearOfPassing());
        cell(t, e.percentage());
      }
      add(doc, t);
    }

    List<WorkingExperience> work = v.workingExperiences();
    if (work != null && !work.isEmpty()) {
      section(doc, "Working Experience");
      PdfPTable t =
          grid(
              new String[] {"Organization", "Period", "Designation", "Salary/CTC", "Reason for Leaving"},
              new float[] {26, 16, 20, 16, 22});
      for (WorkingExperience w : work) {
        cell(t, w.organization());
        cell(t, w.period());
        cell(t, w.designation());
        cell(t, w.salaryCtc());
        cell(t, w.reasonForLeaving());
      }
      add(doc, t);
    }

    List<FamilyDetail> fam = v.familyDetails();
    if (fam != null && !fam.isEmpty()) {
      section(doc, "Family Details");
      PdfPTable t = grid(new String[] {"Name", "Age", "Relation", "Occupation"}, new float[] {34, 12, 24, 30});
      for (FamilyDetail f : fam) {
        cell(t, f.name());
        cell(t, f.age());
        cell(t, f.relation());
        cell(t, f.occupation());
      }
      add(doc, t);
    }

    List<CharacterReference> refs = v.characterReferences();
    if (refs != null && !refs.isEmpty()) {
      section(doc, "Character References");
      PdfPTable t = grid(new String[] {"Name", "Address", "Phone"}, new float[] {30, 46, 24});
      for (CharacterReference r : refs) {
        cell(t, r.name());
        cell(t, r.address());
        cell(t, r.phone());
      }
      add(doc, t);
    }

    if (v.declaration() != null && !v.declaration().isBlank()) {
      section(doc, "Declaration");
      add(doc, new Paragraph(v.declaration(), VALUE));
    }

    signatureBlock(doc, "Candidate Signature", signatureImage, v.updatedAt());
    return close(doc, out);
  }

  // --- Form 2 ---------------------------------------------------------------

  static byte[] form2(String companyName, String employeeCode, Form2View v, byte[] signatureImage) {
    Document doc = open();
    ByteArrayOutputStream out = writer(doc);
    header(doc, companyName, "Form 2 — Employee Info", employeeCode);
    if (v == null) {
      add(doc, new Paragraph("This form was not completed.", VALUE));
      return close(doc, out);
    }
    PdfPTable kv = kvTable();
    kv(kv, "Full Name", v.fullName());
    kv(kv, "Father's Name", v.fatherName());
    kv(kv, "Employee ID", employeeCode); // system-assigned; blank until approval
    kv(kv, "Spark ID", v.sparkId());
    kv(kv, "Date of Birth", v.dateOfBirth());
    kv(kv, "Date of Joining", v.dateOfJoining());
    kv(kv, "Blood Group", v.bloodGroup());
    kv(kv, "Mobile", v.mobile());
    kv(kv, "Alternate Number", v.alternateNumber());
    kv(kv, "Official Email", v.officialEmail());
    kv(kv, "Personal Email", v.personalEmail());
    kv(kv, "Designation", v.designation());
    kv(kv, "Documents Submitted", v.documentSubmitted());
    kv(kv, "Vehicle No (2W/4W)", v.vehicleNo2W4W());
    kv(kv, "PAN Number", v.panNumber());
    kv(kv, "Axis Account Number", v.axisAccountNumber());
    kv(kv, "Current Address", v.currentAddress());
    kv(kv, "Permanent Address", v.permanentAddress());
    add(doc, kv);

    signatureBlock(doc, "Candidate Signature", signatureImage, v.updatedAt());
    return close(doc, out);
  }

  // --- Form 3 ---------------------------------------------------------------

  static byte[] form3(String companyName, String employeeCode, List<Form3EntryView> entries) {
    Document doc = open();
    ByteArrayOutputStream out = writer(doc);
    header(doc, companyName, "Form 3 — Previous Employment", employeeCode);
    if (entries == null || entries.isEmpty()) {
      add(doc, new Paragraph("No previous employment declared.", VALUE));
      return close(doc, out);
    }
    int i = 1;
    for (Form3EntryView e : entries) {
      section(doc, "Employer " + i++);
      PdfPTable kv = kvTable();
      kv(kv, "Company Name", e.companyName());
      kv(kv, "Company Address", e.companyAddress());
      kv(kv, "Date of Joining", e.dateOfJoining());
      kv(kv, "Date of Relieving", e.dateOfRelieving());
      kv(kv, "Designation", e.designation());
      kv(kv, "Last Drawn Salary", e.lastDrawnSalary());
      kv(kv, "Job Type", e.jobType());
      kv(kv, "Reason for Leaving", e.reasonForLeaving());
      kv(kv, "Reporting To", e.reportingTo());
      kv(kv, "RO Contact", e.roContact());
      kv(kv, "HR Name / Contact", e.hrNameContact());
      add(doc, kv);
    }
    return close(doc, out);
  }

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

  // --- merge ----------------------------------------------------------------

  static byte[] merge(List<byte[]> pdfs) {
    Document doc = new Document();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      PdfCopy copy = new PdfCopy(doc, out);
      doc.open();
      for (byte[] pdf : pdfs) {
        PdfReader reader = new PdfReader(pdf);
        int pages = reader.getNumberOfPages();
        for (int i = 1; i <= pages; i++) {
          copy.addPage(copy.getImportedPage(reader, i));
        }
        copy.freeReader(reader);
        reader.close();
      }
    } catch (Exception e) {
      throw new IllegalStateException("PDF merge failed", e);
    } finally {
      if (doc.isOpen()) {
        doc.close();
      }
    }
    return out.toByteArray();
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

  private static void section(Document doc, String title) {
    Paragraph p = new Paragraph(title, HEADING);
    p.setSpacingBefore(10);
    p.setSpacingAfter(2);
    add(doc, p);
  }

  private static PdfPTable kvTable() {
    PdfPTable t = new PdfPTable(2);
    t.setWidthPercentage(100);
    setWidths(t, new float[] {32, 68});
    t.setSpacingBefore(6);
    return t;
  }

  private static void kv(PdfPTable t, String label, String value) {
    PdfPCell l = new PdfPCell(new Phrase(label, LABEL));
    l.setBorder(Rectangle.NO_BORDER);
    l.setPaddingBottom(3);
    t.addCell(l);
    PdfPCell val = new PdfPCell(new Phrase(value == null ? "" : value, VALUE));
    val.setBorder(Rectangle.NO_BORDER);
    val.setPaddingBottom(3);
    t.addCell(val);
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

  private static void signatureBlock(Document doc, String label, byte[] signatureImage, String dateText) {
    add(doc, rule());
    PdfPTable t = new PdfPTable(2);
    t.setWidthPercentage(100);
    setWidths(t, new float[] {62, 38});
    t.setSpacingBefore(16);
    PdfPCell sig = new PdfPCell();
    sig.setBorder(Rectangle.NO_BORDER);
    sig.addElement(new Phrase(label, LABEL));
    if (signatureImage != null) {
      try {
        Image img = Image.getInstance(signatureImage);
        img.scaleToFit(170, 64);
        sig.addElement(img);
      } catch (Exception e) {
        sig.addElement(new Phrase("(signature on file)", VALUE));
      }
    } else {
      sig.addElement(new Phrase("________________________", VALUE));
    }
    t.addCell(sig);
    PdfPCell date = new PdfPCell();
    date.setBorder(Rectangle.NO_BORDER);
    date.addElement(new Phrase("Date", LABEL));
    date.addElement(new Phrase(dateText == null ? "" : dateText, VALUE));
    t.addCell(date);
    add(doc, t);
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
