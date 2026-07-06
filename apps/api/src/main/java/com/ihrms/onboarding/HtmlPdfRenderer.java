package com.ihrms.onboarding;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.slf4j.Slf4jLogger;
import com.openhtmltopdf.util.XRLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders the faithful Form 1/2/3 layouts: binds the collected data into the XHTML templates under
 * {@code templates/pdf/} with Thymeleaf (XML mode → well-formed output), then rasterises to PDF with
 * openhtmltopdf-pdfbox. A bundled sans-serif TTF (Liberation Sans, Arial-metric) is registered as the
 * body font so text embeds reliably. Also merges the five per-form PDFs into the complete application
 * via PDFBox. Form 4 (documents manifest) is produced by {@link PdfRenderer} and is unchanged.
 */
@Component
public class HtmlPdfRenderer {

  static {
    // Route openhtmltopdf's internal logging through slf4j instead of java.util.logging.
    XRLog.setLoggerImpl(new Slf4jLogger());
  }

  private static final String REGULAR_FONT_PATH = "/fonts/LiberationSans-Regular.ttf";
  private static final String BOLD_FONT_PATH = "/fonts/LiberationSans-Bold.ttf";

  private final TemplateEngine engine;
  private final byte[] fontBytes;
  private final byte[] boldFontBytes;

  public HtmlPdfRenderer() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/pdf/");
    resolver.setSuffix(".html");
    // XML mode: the output is well-formed XHTML (self-closed tags, escaped entities) that
    // openhtmltopdf's XML parser accepts directly.
    resolver.setTemplateMode(TemplateMode.XML);
    resolver.setCharacterEncoding("UTF-8");
    resolver.setCacheable(true);
    TemplateEngine te = new TemplateEngine();
    te.setTemplateResolver(resolver);
    this.engine = te;
    this.fontBytes = loadFontBytes(REGULAR_FONT_PATH);
    this.boldFontBytes = loadFontBytes(BOLD_FONT_PATH);
  }

  /** Bind {@code model} into {@code templateName} (e.g. "form1") and render it to a PDF. */
  public byte[] render(String templateName, Map<String, Object> model) {
    Context ctx = new Context();
    ctx.setVariables(model);
    String xhtml = engine.process(templateName, ctx);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PdfRendererBuilder builder = new PdfRendererBuilder();
    builder.useFastMode();
    registerFonts(builder);
    builder.withHtmlContent(xhtml, "");
    builder.toStream(out);
    try {
      builder.run();
    } catch (IOException e) {
      throw new IllegalStateException("PDF rendering failed for template " + templateName, e);
    }
    return out.toByteArray();
  }

  /** Merge PDFs (in order) into one document via PDFBox. */
  public byte[] merge(List<byte[]> pdfs) {
    PDFMergerUtility merger = new PDFMergerUtility();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    merger.setDestinationStream(out);
    for (byte[] pdf : pdfs) {
      merger.addSource(new ByteArrayInputStream(pdf));
    }
    try {
      merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
    } catch (IOException e) {
      throw new IllegalStateException("PDF merge failed", e);
    }
    return out.toByteArray();
  }

  /**
   * Register the bundled TTFs under the template font-family names: the Regular face for normal
   * weight and the Bold face for bold weight, so {@code font-weight:bold} in the templates (titles,
   * table headers) renders as true bold rather than synthetic/regular.
   */
  private void registerFonts(PdfRendererBuilder builder) {
    for (String family : List.of("Helvetica", "Arial")) {
      builder.useFont(() -> new ByteArrayInputStream(fontBytes), family, 400, FontStyle.NORMAL, true);
      builder.useFont(() -> new ByteArrayInputStream(boldFontBytes), family, 700, FontStyle.NORMAL, true);
    }
  }

  private static byte[] loadFontBytes(String path) {
    try (InputStream in = HtmlPdfRenderer.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Bundled PDF font not found on classpath: " + path);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException("Could not load bundled PDF font " + path, e);
    }
  }
}
