package com.ihrms.domain.repository;

import com.ihrms.domain.model.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, String> {

  /** The acting user's Sent box — messages they sent, newest first. */
  Page<Message> findBySenderUserIdOrderByCreatedAtDesc(String senderUserId, Pageable pageable);
}
