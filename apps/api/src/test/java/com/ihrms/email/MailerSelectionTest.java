package com.ihrms.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The outbound {@link Mailer} is selected by config presence (no Spring context / DB / network): SMTP_HOST
 * present + a JavaMailSender available → {@link SmtpMailer} (isReal); absent → {@link DevLogMailer} (not).
 */
class MailerSelectionTest {

  private final MailConfig config = new MailConfig();

  @Test
  void devLogWhenNoSmtpHost() {
    Mailer mailer = config.mailer(props(null), providerOf(null));
    assertThat(mailer).isInstanceOf(DevLogMailer.class);
    assertThat(mailer.isReal()).isFalse();
  }

  @Test
  void devLogWhenHostBlank() {
    Mailer mailer = config.mailer(props("   "), providerOf(new JavaMailSenderImpl()));
    assertThat(mailer).isInstanceOf(DevLogMailer.class);
    assertThat(mailer.isReal()).isFalse();
  }

  @Test
  void smtpWhenHostPresentAndSenderAvailable() {
    Mailer mailer = config.mailer(props("smtp.zoho.in"), providerOf(new JavaMailSenderImpl()));
    assertThat(mailer).isInstanceOf(SmtpMailer.class);
    assertThat(mailer.isReal()).isTrue();
  }

  @Test
  void fallsBackToDevLogWhenHostSetButNoSender() {
    Mailer mailer = config.mailer(props("smtp.zoho.in"), providerOf(null));
    assertThat(mailer).isInstanceOf(DevLogMailer.class);
    assertThat(mailer.isReal()).isFalse();
  }

  // --- helpers ---------------------------------------------------------------

  private static AppProperties props(String mailHost) {
    return new AppProperties(
        null, null, null, null, null, null, null, 0, null,
        new AppProperties.Mail(mailHost, 587, null, null, "hrorg.in <noreply@hrorg.in>", "support@hrorg.in"),
        null, null, null);
  }

  private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
    return new ObjectProvider<>() {
      @Override
      public JavaMailSender getObject() {
        return sender;
      }

      @Override
      public JavaMailSender getObject(Object... args) {
        return sender;
      }

      @Override
      public JavaMailSender getIfAvailable() {
        return sender;
      }

      @Override
      public JavaMailSender getIfUnique() {
        return sender;
      }
    };
  }
}
