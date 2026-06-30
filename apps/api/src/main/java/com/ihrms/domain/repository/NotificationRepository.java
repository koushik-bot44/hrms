package com.ihrms.domain.repository;

import com.ihrms.domain.model.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, String> {
  List<Notification> findByRecipientUserId(String recipientUserId);
}
