package com.ihrms.domain.repository;

import com.ihrms.domain.model.Message;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, String> {

  /** A thread's messages, oldest first — the conversation in chronological order (§8). */
  List<Message> findByThreadIdOrderByCreatedAtAsc(String threadId);

  /** All messages across a page of threads, for building thread summaries in one batch. */
  List<Message> findByThreadIdInOrderByThreadIdAscCreatedAtAsc(Collection<String> threadIds);

  /**
   * Inbox as THREADS: distinct threads in which the viewer has a non-deleted received message, newest
   * activity first. Paginated (the count is the distinct-thread total).
   */
  @Query(
      value =
          "SELECT m.\"threadId\" FROM \"messages\" m"
              + " JOIN \"message_recipients\" r ON r.\"messageId\" = m.\"id\""
              + " WHERE (r.\"recipientUserId\" = :uid OR r.\"recipientEmployeeId\" = :uid) AND r.\"deletedAt\" IS NULL"
              + " GROUP BY m.\"threadId\" ORDER BY MAX(m.\"createdAt\") DESC",
      countQuery =
          "SELECT count(DISTINCT m.\"threadId\") FROM \"messages\" m"
              + " JOIN \"message_recipients\" r ON r.\"messageId\" = m.\"id\""
              + " WHERE (r.\"recipientUserId\" = :uid OR r.\"recipientEmployeeId\" = :uid) AND r.\"deletedAt\" IS NULL",
      nativeQuery = true)
  Page<String> findInboxThreadIds(@Param("uid") String uid, Pageable pageable);

  /** Sent as THREADS: distinct threads in which the viewer has a non-deleted sent message. */
  @Query(
      value =
          "SELECT m.\"threadId\" FROM \"messages\" m"
              + " WHERE (m.\"senderUserId\" = :uid OR m.\"senderEmployeeId\" = :uid) AND m.\"senderDeletedAt\" IS NULL"
              + " GROUP BY m.\"threadId\" ORDER BY MAX(m.\"createdAt\") DESC",
      countQuery =
          "SELECT count(DISTINCT m.\"threadId\") FROM \"messages\" m"
              + " WHERE (m.\"senderUserId\" = :uid OR m.\"senderEmployeeId\" = :uid) AND m.\"senderDeletedAt\" IS NULL",
      nativeQuery = true)
  Page<String> findSentThreadIds(@Param("uid") String uid, Pageable pageable);

  /**
   * Search the viewer's OWN mail (threads with a message they sent or received, excluding their
   * soft-deleted copies) by case-insensitive subject/body match. Never leaks another mailbox (§8).
   * {@code q} must already be a lowercased {@code %term%} pattern.
   */
  @Query(
      value =
          "SELECT m.\"threadId\" FROM \"messages\" m"
              + " WHERE (((m.\"senderUserId\" = :uid OR m.\"senderEmployeeId\" = :uid) AND m.\"senderDeletedAt\" IS NULL)"
              + "   OR EXISTS (SELECT 1 FROM \"message_recipients\" r WHERE r.\"messageId\" = m.\"id\""
              + "     AND (r.\"recipientUserId\" = :uid OR r.\"recipientEmployeeId\" = :uid) AND r.\"deletedAt\" IS NULL))"
              + " AND (lower(m.\"subject\") LIKE :q OR lower(m.\"body\") LIKE :q)"
              + " GROUP BY m.\"threadId\" ORDER BY MAX(m.\"createdAt\") DESC",
      countQuery =
          "SELECT count(DISTINCT m.\"threadId\") FROM \"messages\" m"
              + " WHERE (((m.\"senderUserId\" = :uid OR m.\"senderEmployeeId\" = :uid) AND m.\"senderDeletedAt\" IS NULL)"
              + "   OR EXISTS (SELECT 1 FROM \"message_recipients\" r WHERE r.\"messageId\" = m.\"id\""
              + "     AND (r.\"recipientUserId\" = :uid OR r.\"recipientEmployeeId\" = :uid) AND r.\"deletedAt\" IS NULL))"
              + " AND (lower(m.\"subject\") LIKE :q OR lower(m.\"body\") LIKE :q)",
      nativeQuery = true)
  Page<String> searchThreadIds(@Param("uid") String uid, @Param("q") String q, Pageable pageable);

  /** Thread-level unread badge (§8): distinct threads with a non-deleted, unopened received message. */
  @Query(
      value =
          "SELECT count(DISTINCT m.\"threadId\") FROM \"messages\" m"
              + " JOIN \"message_recipients\" r ON r.\"messageId\" = m.\"id\""
              + " WHERE (r.\"recipientUserId\" = :uid OR r.\"recipientEmployeeId\" = :uid)"
              + " AND r.\"readAt\" IS NULL AND r.\"deletedAt\" IS NULL",
      nativeQuery = true)
  long countUnreadThreads(@Param("uid") String uid);
}
