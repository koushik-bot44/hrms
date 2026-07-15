package com.ihrms.domain.repository;

import com.ihrms.domain.model.AttendanceBreak;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Breaks within attendance sessions (§8a v2). Loaded by session id; one OPEN break per session. */
public interface AttendanceBreakRepository extends JpaRepository<AttendanceBreak, String> {

  /** The session's currently-open break, if any (drives break start/end + status). */
  Optional<AttendanceBreak> findBySessionIdAndBreakEndAtIsNull(String sessionId);

  /** All breaks for a set of sessions (history/roster) — oldest first for display. */
  List<AttendanceBreak> findBySessionIdInOrderByBreakStartAtAsc(Collection<String> sessionIds);
}
