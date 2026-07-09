package com.ihrms.domain.repository;

import com.ihrms.domain.model.MessageAttachment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, String> {

  /** A message's bound attachments, oldest first. */
  List<MessageAttachment> findByMessageIdOrderByCreatedAtAsc(String messageId);

  /** Attachments bound to any of these messages — batch metadata for a thread / list page. */
  List<MessageAttachment> findByMessageIdInOrderByCreatedAtAsc(Collection<String> messageIds);
}
