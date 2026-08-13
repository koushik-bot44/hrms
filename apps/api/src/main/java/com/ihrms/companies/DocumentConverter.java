package com.ihrms.companies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Word → PDF conversion for the letterhead upload (§3.5). The letterhead is a VISUAL template, so faithful
 * reproduction matters; the only tool that renders .docx/.doc faithfully is LibreOffice headless. This is a
 * SEAM: it converts via {@code soffice --headless --convert-to pdf} when a LibreOffice binary is resolvable
 * (env {@code LIBREOFFICE_PATH}, the {@code ihrms.libreoffice.path} property, or {@code soffice}/{@code
 * libreoffice} on PATH), and otherwise reports itself UNAVAILABLE so the caller can tell the user to export to
 * PDF. Provisioning LibreOffice later makes Word "just work" with no code change.
 *
 * <p>Neither this dev environment nor the slim {@code eclipse-temurin:21-jre} runtime ships LibreOffice, so in
 * practice this reports unavailable today and Word uploads are refused with a clear message — PDF is accepted.
 */
@Component
public class DocumentConverter {

  private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);
  private static final long TIMEOUT_SECONDS = 90;

  /** Thrown when no LibreOffice binary is available — the caller degrades to "please upload a PDF". */
  public static class ConversionUnavailableException extends RuntimeException {
    public ConversionUnavailableException(String message) {
      super(message);
    }
  }

  /** Thrown when a binary IS present but the conversion itself failed (bad file, timeout, non-zero exit). */
  public static class ConversionFailedException extends RuntimeException {
    public ConversionFailedException(String message) {
      super(message);
    }
  }

  private final String configuredPath;

  public DocumentConverter(@Value("${ihrms.libreoffice.path:}") String configuredPath) {
    this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
  }

  /** Whether a LibreOffice binary is resolvable right now (drives the UI's Word-upload capability flag). */
  public boolean isAvailable() {
    return resolveBinary() != null;
  }

  /**
   * Convert a Word document to PDF bytes. {@code ext} is the source extension without the dot (docx|doc) — used
   * only to name the temp input so LibreOffice picks the right filter.
   *
   * @throws ConversionUnavailableException if no LibreOffice binary is resolvable
   * @throws ConversionFailedException if the conversion fails
   */
  public byte[] wordToPdf(byte[] wordBytes, String ext) {
    String bin = resolveBinary();
    if (bin == null) {
      throw new ConversionUnavailableException(
          "Word conversion is not available on this server. Please export your letterhead to PDF and upload the PDF.");
    }
    String safeExt = (ext == null || ext.isBlank()) ? "docx" : ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    Path dir = null;
    try {
      dir = Files.createTempDirectory("ihrms-lh-");
      Path in = dir.resolve("letterhead." + safeExt);
      Files.write(in, wordBytes);

      Process proc =
          new ProcessBuilder(
                  bin,
                  "--headless",
                  "--norestore",
                  "--convert-to",
                  "pdf",
                  "--outdir",
                  dir.toString(),
                  in.toString())
              .redirectErrorStream(true)
              .start();
      // Drain output so the process can't block on a full pipe; bounded by the timeout below.
      byte[] procOut = proc.getInputStream().readAllBytes();
      boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        throw new ConversionFailedException("Converting the Word file timed out — please upload a PDF instead.");
      }
      if (proc.exitValue() != 0) {
        log.warn("LibreOffice conversion exit {}: {}", proc.exitValue(), new String(procOut));
        throw new ConversionFailedException("We couldn't convert that Word file — please upload a PDF instead.");
      }
      Path out = dir.resolve("letterhead.pdf");
      if (!Files.exists(out)) {
        log.warn("LibreOffice produced no PDF: {}", new String(procOut));
        throw new ConversionFailedException("We couldn't convert that Word file — please upload a PDF instead.");
      }
      return Files.readAllBytes(out);
    } catch (IOException e) {
      throw new ConversionFailedException("We couldn't convert that Word file — please upload a PDF instead.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ConversionFailedException("The Word conversion was interrupted — please try again.");
    } finally {
      if (dir != null) {
        deleteQuietly(dir);
      }
    }
  }

  /** Resolve a usable binary: the configured path first, then common PATH names / install locations. */
  private String resolveBinary() {
    if (!configuredPath.isEmpty() && Files.isExecutable(Path.of(configuredPath))) {
      return configuredPath;
    }
    for (String candidate :
        List.of(
            "soffice",
            "libreoffice",
            "/usr/bin/soffice",
            "/usr/bin/libreoffice",
            "/opt/libreoffice/program/soffice",
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe")) {
      if (candidate.contains("/") || candidate.contains("\\")) {
        if (Files.isExecutable(Path.of(candidate))) {
          return candidate;
        }
      } else if (onPath(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** A bare name is usable if it runs {@code --version} with exit 0 within a couple of seconds. */
  private boolean onPath(String name) {
    try {
      Process p = new ProcessBuilder(name, "--version").redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return false;
      }
      return p.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void deleteQuietly(Path dir) {
    try (var walk = Files.walk(dir)) {
      walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // best effort
                }
              });
    } catch (IOException ignored) {
      // best effort
    }
  }
}
