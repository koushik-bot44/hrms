package com.ihrms.domain.repository;

import com.ihrms.domain.model.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, String> {
  List<Notification> findByRecipientUserId(String recipientUserId);

  List<Notification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId);

  List<Notification> findByRecipientUserIdAndReadFalse(String recipientUserId);

  long countByRecipientUserIdAndReadFalse(String recipientUserId);

  Optional<Notification> findByIdAndRecipientUserId(String id, String recipientUserId);
}
