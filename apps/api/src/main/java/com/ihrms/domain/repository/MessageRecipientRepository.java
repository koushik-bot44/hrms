package com.ihrms.domain.repository;

import com.ihrms.domain.model.MessageRecipient;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRecipientRepository extends JpaRepository<MessageRecipient, String> {

  /** The acting user's own recipient row for a message — access gate + read-state stamp. */
  Optional<MessageRecipient> findByMessageIdAndRecipientUserId(String messageId, String recipientUserId);

  /** All recipients of a message (to display "to" / resolve participants). */
  List<MessageRecipient> findByMessageId(String messageId);

  /** Every recipient row across a set of messages — batch participant/visibility for a thread page. */
  List<MessageRecipient> findByMessageIdIn(Collection<String> messageIds);

  /** The viewer's recipient rows across a set of messages — batch read/visibility for a thread page. */
  List<MessageRecipient> findByRecipientUserIdAndMessageIdIn(
      String recipientUserId, Collection<String> messageIds);
}
