/**
 * A minimal, NON-SENSITIVE session-hint cookie on the WEB origin — the single signal `middleware.ts` uses to
 * redirect logged-in visitors off the marketing `/`. It is NOT the session: it carries no token, only the
 * boolean fact that a session was established in this browser. The real auth stays exactly as before (access
 * token in memory + the httpOnly `ihrms_refresh` cookie on the API origin, path `/auth`, which never reaches
 * the web origin). Presence-only by design: a stale marker simply lands the visitor on /login, which forwards
 * a live session to its home or shows the sign-in form — the same behaviour as today.
 *
 * Non-httpOnly on purpose so the client can set/clear it via document.cookie; middleware reads it server-side.
 * The constant is shared with middleware.ts so the name can never drift (this module is edge-safe: no
 * top-level `document`/`location` access — those live inside the functions, called only in the browser).
 */
export const WEB_SESSION_HINT = 'ihrms_web_session';

/** Mark that a session exists in this browser (called when auth is applied). */
export function markWebSession(): void {
  if (typeof document === 'undefined') return;
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  // ~30 days; refreshed on every successful auth. Staleness self-heals: a failed refresh calls clearWebSession.
  document.cookie = `${WEB_SESSION_HINT}=1; Path=/; Max-Age=2592000; SameSite=Lax${secure}`;
}

/** Clear the marker (called on logout and on a failed silent refresh). */
export function clearWebSession(): void {
  if (typeof document === 'undefined') return;
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = `${WEB_SESSION_HINT}=; Path=/; Max-Age=0; SameSite=Lax${secure}`;
}
