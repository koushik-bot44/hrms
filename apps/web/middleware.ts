import { NextResponse, type NextRequest } from 'next/server';
import { WEB_SESSION_HINT } from '@/lib/auth/web-session-hint';

/**
 * Marketing `/` gate. A visitor who already has an app session in this browser is redirected server-side to
 * /login — which already forwards a live session to its role home (we REUSE that home-resolution rather than
 * reimplement it), so logged-in staff and employees land in the app with NO flash of the marketing page.
 *
 * This is a PRESENCE check only of the non-sensitive `ihrms_web_session` marker (set by AuthProvider) — no
 * validation, no API/network call. A stale/expired marker simply lands on /login, which forwards a live
 * session or shows the sign-in form: exactly the behaviour as before. Without the marker (e.g. incognito),
 * the request falls through and the static marketing page is served unchanged.
 *
 * The matcher is scoped to `/` ONLY, so this middleware runs on no other route — every other URL is untouched.
 */
export function middleware(request: NextRequest) {
  if (request.cookies.has(WEB_SESSION_HINT)) {
    return NextResponse.redirect(new URL('/login', request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: '/',
};
