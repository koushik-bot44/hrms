package com.ihrms.domain.repository;

import com.ihrms.domain.model.MessageRecipient;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRecipientRepository extends JpaRepository<MessageRecipient, String> {

  /** The acting user's Inbox — messages addressed to them, newest message first. */
  @Query(
      value =
          "select r from MessageRecipient r, Message m "
              + "where m.id = r.messageId and r.recipientUserId = :uid order by m.createdAt desc",
      countQuery = "select count(r) from MessageRecipient r where r.recipientUserId = :uid")
  Page<MessageRecipient> findInbox(@Param("uid") String uid, Pageable pageable);

  /** Unread-count badge (§8): the recipient's messages not yet opened. */
  long countByRecipientUserIdAndReadAtIsNull(String recipientUserId);

  /** The acting user's own recipient row for a message — access gate + read-state stamp. */
  Optional<MessageRecipient> findByMessageIdAndRecipientUserId(String messageId, String recipientUserId);

  /** All recipients of a message (to display "to" on the sent/message view). */
  List<MessageRecipient> findByMessageId(String messageId);
}
