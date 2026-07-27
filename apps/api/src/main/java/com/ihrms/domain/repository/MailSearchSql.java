package com.ihrms.domain.repository;

/**
 * The native SQL fragments for the ONE mail search+filter query in {@link MessageRepository#searchThreadIds}.
 * These are compile-time {@code String} constants (literal concatenation only — no method calls) so they can
 * be used as {@code @Query} annotation values. The base is participant-scoped; every filter clause is
 * null-guarded ({@code :param IS NULL OR …}) and ANDed, so it only ever NARROWS the viewer's own mail. See
 * ARCHITECTURE.md §8.
 */
final class MailSearchSql {

  private MailSearchSql() {}

  static final String SELECT = "SELECT m.\"threadId\" FROM \"messages\" m";

  static final String WHERE =
      " WHERE "
          // base: a message the viewer sent (non-deleted) OR received (non-deleted) — the participant scope
          + "(((m.\"senderUserId\" = :uid OR m.\"senderEmployeeId\" = :uid) AND m.\"senderDeletedAt\" IS NULL)"
          + " OR EXISTS (SELECT 1 FROM \"message_recipients\" r WHERE r.\"messageId\" = m.\"id\""
          + "   AND (r.\"recipientUserId\" = :uid OR r.\"recipientEmployeeId\" = :uid) AND r.\"deletedAt\" IS NULL))"
          // text q — subject/body, case-insensitive (null = no text filter). CAST so PG can type the
          // parameter even when it is NULL (a bare ":q IS NULL" on an untyped null errors on Postgres).
          + " AND (CAST(:q AS text) IS NULL OR (lower(m.\"subject\") LIKE :q OR lower(m.\"body\") LIKE :q))"
          // from — a viewer-visible message in the thread whose SENDER matches by address OR name
          + " AND (CAST(:sender AS text) IS NULL OR EXISTS (SELECT 1 FROM \"messages\" mf WHERE mf.\"threadId\" = m.\"threadId\""
          + "   AND (((mf.\"senderUserId\" = :uid OR mf.\"senderEmployeeId\" = :uid) AND mf.\"senderDeletedAt\" IS NULL)"
          + "     OR EXISTS (SELECT 1 FROM \"message_recipients\" rf WHERE rf.\"messageId\" = mf.\"id\""
          + "       AND (rf.\"recipientUserId\" = :uid OR rf.\"recipientEmployeeId\" = :uid) AND rf.\"deletedAt\" IS NULL))"
          + "   AND (EXISTS (SELECT 1 FROM \"users\" u WHERE u.\"id\" = mf.\"senderUserId\""
          + "         AND (lower(u.\"email\") LIKE :sender OR lower(u.\"name\") LIKE :sender))"
          + "     OR EXISTS (SELECT 1 FROM \"employees\" e WHERE e.\"id\" = mf.\"senderEmployeeId\""
          + "         AND (lower(e.\"mailAddress\") LIKE :sender OR lower(e.\"fullName\") LIKE :sender)))))"
          // hasAttachment — a viewer-visible message in the thread carries an attachment
          + " AND (:hasAttachment = FALSE OR EXISTS (SELECT 1 FROM \"message_attachments\" a"
          + "   JOIN \"messages\" ma ON ma.\"id\" = a.\"messageId\" WHERE ma.\"threadId\" = m.\"threadId\""
          + "   AND (((ma.\"senderUserId\" = :uid OR ma.\"senderEmployeeId\" = :uid) AND ma.\"senderDeletedAt\" IS NULL)"
          + "     OR EXISTS (SELECT 1 FROM \"message_recipients\" ra WHERE ra.\"messageId\" = ma.\"id\""
          + "       AND (ra.\"recipientUserId\" = :uid OR ra.\"recipientEmployeeId\" = :uid) AND ra.\"deletedAt\" IS NULL))))"
          // unread — the viewer has an unopened, non-deleted received message in the thread
          + " AND (:unread = FALSE OR EXISTS (SELECT 1 FROM \"message_recipients\" ru"
          + "   JOIN \"messages\" mu ON mu.\"id\" = ru.\"messageId\" WHERE mu.\"threadId\" = m.\"threadId\""
          + "   AND (ru.\"recipientUserId\" = :uid OR ru.\"recipientEmployeeId\" = :uid)"
          + "   AND ru.\"readAt\" IS NULL AND ru.\"deletedAt\" IS NULL))"
          // starred — the viewer starred the thread
          + " AND (:starred = FALSE OR EXISTS (SELECT 1 FROM \"thread_stars\" s"
          + "   WHERE s.\"threadId\" = m.\"threadId\" AND s.\"accountId\" = :uid))"
          // scope — reuse each view's own rule (INBOX excludes the viewer's archived threads)
          + " AND (:scope = 'ALL'"
          + "   OR (:scope = 'INBOX'"
          + "     AND EXISTS (SELECT 1 FROM \"messages\" mi JOIN \"message_recipients\" ri ON ri.\"messageId\" = mi.\"id\""
          + "       WHERE mi.\"threadId\" = m.\"threadId\" AND (ri.\"recipientUserId\" = :uid OR ri.\"recipientEmployeeId\" = :uid)"
          + "       AND ri.\"deletedAt\" IS NULL)"
          + "     AND NOT EXISTS (SELECT 1 FROM \"thread_archives\" ai WHERE ai.\"threadId\" = m.\"threadId\" AND ai.\"accountId\" = :uid))"
          + "   OR (:scope = 'SENT' AND EXISTS (SELECT 1 FROM \"messages\" ms WHERE ms.\"threadId\" = m.\"threadId\""
          + "       AND (ms.\"senderUserId\" = :uid OR ms.\"senderEmployeeId\" = :uid) AND ms.\"senderDeletedAt\" IS NULL))"
          + "   OR (:scope = 'STARRED' AND EXISTS (SELECT 1 FROM \"thread_stars\" ss WHERE ss.\"threadId\" = m.\"threadId\" AND ss.\"accountId\" = :uid))"
          + "   OR (:scope = 'ARCHIVE' AND EXISTS (SELECT 1 FROM \"thread_archives\" ar WHERE ar.\"threadId\" = m.\"threadId\" AND ar.\"accountId\" = :uid)))";

  // GROUP BY thread + optional date bound on the thread's LATEST activity (both inclusive; null = unbounded).
  static final String GROUP =
      " GROUP BY m.\"threadId\""
          + " HAVING (CAST(:after AS timestamp) IS NULL OR MAX(m.\"createdAt\") >= :after)"
          + " AND (CAST(:before AS timestamp) IS NULL OR MAX(m.\"createdAt\") <= :before)";
}
