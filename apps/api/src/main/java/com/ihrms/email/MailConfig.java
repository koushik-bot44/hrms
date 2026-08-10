package com.ihrms.email;

import com.ihrms.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Selects the outbound {@link Mailer} by config presence and reports it once at startup (contract §Outbound
 * email). {@code SMTP_HOST} set → {@link SmtpMailer} on the auto-configured {@link JavaMailSender}; absent →
 * {@link DevLogMailer} (the default for local dev + all tests). Also provides the small daemon executor the
 * mail dispatch runs on, so a slow/failing SMTP never blocks a request thread.
 */
@Configuration
public class MailConfig {

  private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

  @Bean
  public Mailer mailer(AppProperties props, ObjectProvider<JavaMailSender> senderProvider) {
    AppProperties.Mail mail = props.mail();
    String host = mail == null ? null : mail.host();
    if (host != null && !host.isBlank()) {
      JavaMailSender sender = senderProvider.getIfAvailable();
      if (sender != null) {
        log.info("Mail: SMTP via {}", host);
        return new SmtpMailer(sender, mail.from(), mail.replyTo());
      }
      log.warn("Mail: SMTP_HOST set but no JavaMailSender available — falling back to dev-log");
    }
    log.info("Mail: dev-log (no SMTP configured; MAIL/OTP contents are logged, not emailed)");
    return new DevLogMailer();
  }

  /** Small daemon pool for after-commit mail delivery — keeps SMTP off the request thread. */
  @Bean(name = "mailExecutor")
  public TaskExecutor mailExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(3);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("mail-");
    executor.setDaemon(true);
    executor.initialize();
    return executor;
  }
}
