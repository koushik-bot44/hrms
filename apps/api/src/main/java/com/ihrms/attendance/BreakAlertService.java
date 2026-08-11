package com.ihrms.attendance;

import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.repository.AttendanceBreakRepository;
import com.ihrms.domain.repository.NotificationRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The durable, transactional half of the long-open-break HR alert (§8a). Kept SEPARATE from
 * {@link BreakAlertScanner} so its {@code @Transactional} runs through the Spring proxy (self-invocation
 * would bypass it) and, crucially, so the scanner can fire push + email AFTER this commits (the after-commit
 * convention). Stamping {@code alertSentAt} is what dedupes the scan — a break is alerted exactly once, even
 * if the employee never ends it (or if push/email later fail).
 */
@Service
public class BreakAlertService {

  private final AttendanceBreakRepository breaks;
  private final NotificationRepository notifications;

  public BreakAlertService(AttendanceBreakRepository breaks, NotificationRepository notifications) {
    this.breaks = breaks;
    this.notifications = notifications;
  }

  /**
   * In ONE transaction: stamp the break as alerted (dedupe) and — when the HR is resolved — save the durable
   * HR notification. An unresolved HR ({@code hrUserId == null}) still stamps the break so the scan never
   * re-processes it (the caller logs the skip). Returns nothing; push + email are fired by the caller after
   * this commits.
   */
  @Transactional
  public void recordAlert(String breakId, String hrUserId, String employeeId) {
    breaks
        .findById(breakId)
        .ifPresent(
            b -> {
              b.setAlertSentAt(Instant.now());
              breaks.save(b);
            });
    if (hrUserId != null) {
      Notification n = new Notification();
      n.setRecipientUserId(hrUserId);
      n.setType(NotificationType.EMPLOYEE_LONG_BREAK);
      n.setEmployeeId(employeeId);
      notifications.save(n);
    }
  }
}
