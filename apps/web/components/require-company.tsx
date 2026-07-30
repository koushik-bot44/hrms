'use client';

import * as React from 'react';
import { useParams, usePathname, useRouter } from 'next/navigation';
import { ShieldCheck } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';
import { buildCompanyPath } from '@/lib/company-url';

/** The `/{slug}` prefix stripped off a pathname → the company-scoped sub-path (e.g. `/hr/employees`). */
function subPathUnderSlug(pathname: string, slug: string): string {
  const prefix = `/${slug}`;
  if (pathname === prefix) return '';
  if (pathname.startsWith(`${prefix}/`)) return pathname.slice(prefix.length);
  return '';
}

/**
 * TENANCY guard for the `/[companySlug]/…` tree (Stage 2). It composes WITH {@link RequireRole} (which
 * checks the ROLE) — this checks only that the URL slug matches the SESSION's own company:
 * - loading → a checking placeholder;
 * - unauthenticated → /login (each area's RequireRole also handles the per-audience sign-in door);
 * - a PLATFORM role (no companySlug) on a slugged path → their platform home;
 * - a company-scoped session on ANOTHER slug → the SAME sub-path under THEIR own slug (deep-link
 *   preserving). Anti-enumeration (Stage 1): a real other company and a non-existent slug are
 *   indistinguishable to a company-scoped client — both redirect home rather than leak existence;
 * - match → render.
 * Client-side, mirroring the app's client auth model (AuthProvider + RequireRole); the server still
 * enforces tenancy on every request (§6) — this is UX.
 */
export function RequireCompany({ children }: { children: React.ReactNode }) {
  const { session, status } = useAuth();
  const params = useParams();
  const pathname = usePathname();
  const router = useRouter();

  const urlSlug = String(params.companySlug ?? '');
  const sessionSlug = session?.companySlug ?? null;
  const matched = Boolean(sessionSlug) && sessionSlug === urlSlug;

  React.useEffect(() => {
    if (status === 'loading') return;
    if (status === 'unauthenticated' || !session) {
      router.replace('/login');
      return;
    }
    if (matched) return;
    if (!sessionSlug) {
      // A platform role (SUPER_ADMIN / ACCOUNTS_ADMIN / HIERARCHY) hit a slugged path.
      router.replace(homePathForSession(session));
      return;
    }
    // A company-scoped session on a slug that isn't theirs → their own slug, same sub-path.
    router.replace(buildCompanyPath(sessionSlug, subPathUnderSlug(pathname, urlSlug)));
  }, [status, session, matched, sessionSlug, urlSlug, pathname, router]);

  if (status !== 'authenticated' || !session || !matched) {
    return (
      <div className="flex min-h-dvh items-center justify-center" role="status" aria-busy="true">
        <div className="flex items-center gap-2 text-muted-foreground">
          <ShieldCheck className="size-4 animate-pulse" />
          <span className="text-sm">Checking access…</span>
        </div>
      </div>
    );
  }
  return <>{children}</>;
}
