package com.ihrms.email;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single minimal branded HTML wrapper for every outbound email (contract §Outbound email). Brand name +
 * indigo accent header, a plain content area holding the message's existing body, and a footer line
 * "Questions? support@hrorg.in". There is deliberately NO per-mail template system — one wrapper, and the
 * per-mail content/links (unchanged) go inside it. The plain-text body is sent as the alternative part.
 */
public final class MailTemplate {

  private MailTemplate() {}

  private static final String INDIGO = "#4F46E5";
  private static final Pattern URL = Pattern.compile("(https?://[^\\s<>\"]+)");

  /** Wrap the plain-text message body into the branded HTML email (platform brand line — no slug). */
  public static String render(String bodyText) {
    return render(bodyText, null);
  }

  /**
   * Wrap the body into the branded HTML email. {@code brandSlug} slugs the header brand line to
   * {@code hrorg.in/{slug}} for a COMPANY-user email (matching their slugged links); null keeps {@code hrorg.in}
   * for a platform user.
   */
  public static String render(String bodyText, String brandSlug) {
    String content = linkify(escape(bodyText == null ? "" : bodyText)).replace("\n", "<br>");
    String brand =
        brandSlug == null || brandSlug.isBlank() ? "hrorg.in" : "hrorg.in/" + escape(brandSlug);
    return "<!doctype html><html><body style=\"margin:0;background:#f5f6fb;"
        + "font-family:Segoe UI,Helvetica,Arial,sans-serif;color:#0f172a;\">"
        + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\">"
        + "<tr><td align=\"center\" style=\"padding:28px 16px;\">"
        + "<table role=\"presentation\" width=\"520\" cellpadding=\"0\" cellspacing=\"0\" "
        + "style=\"max-width:520px;background:#ffffff;border-radius:12px;overflow:hidden;"
        + "border:1px solid #e6e8f0;\">"
        // header
        + "<tr><td style=\"padding:20px 28px;border-bottom:1px solid #eef0f6;\">"
        + "<span style=\"font-size:18px;font-weight:700;color:" + INDIGO + ";\">" + brand + "</span>"
        + "<span style=\"font-size:12px;color:#64748b;\">&nbsp;&middot;&nbsp;Integrated HR Management Services</span>"
        + "</td></tr>"
        // content
        + "<tr><td style=\"padding:26px 28px;font-size:15px;line-height:1.6;color:#0f172a;\">"
        + content
        + "</td></tr>"
        // footer
        + "<tr><td style=\"padding:18px 28px;border-top:1px solid #eef0f6;font-size:12px;color:#64748b;\">"
        + "Questions? <a href=\"mailto:support@hrorg.in\" style=\"color:" + INDIGO + ";\">support@hrorg.in</a>"
        + "</td></tr>"
        + "</table></td></tr></table></body></html>";
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Turn bare http(s) URLs into links (content is already HTML-escaped, so URLs contain no unsafe chars). */
  private static String linkify(String escaped) {
    Matcher m = URL.matcher(escaped);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String url = m.group(1);
      m.appendReplacement(
          out, Matcher.quoteReplacement("<a href=\"" + url + "\" style=\"color:" + INDIGO + ";\">" + url + "</a>"));
    }
    m.appendTail(out);
    return out.toString();
  }
}
