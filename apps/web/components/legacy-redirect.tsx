'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { ShieldCheck } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';

/**
 * Transition safety (Stage 2): an OLD top-level area path — `/hr`, `/manager`, `/company-admin`,
 * `/accountant`, `/workspace`, `/employee` and any sub-path under them — redirects to the session's
 * Stage-2 home (slugged for company roles, top-level for platform roles), so old bookmarks/links don't
 * 404 while external deep-links are migrated (Stage 3). It lands the caller in THEIR correct home
 * (`homePathForSession` already routes ACCOUNTANT → `/{slug}/accountant`, ACCOUNTS_ADMIN → `/accounts`,
 * a PASSWORD employee → `/{slug}/workspace`, an OTP employee → `/{slug}/employee`, etc.). Unauthenticated
 * → /login.
 */
export function LegacyRedirect() {
  const { session, status } = useAuth();
  const router = useRouter();

  React.useEffect(() => {
    if (status === 'loading') return;
    router.replace(session ? homePathForSession(session) : '/login');
  }, [status, session, router]);

  return (
    <div className="flex min-h-dvh items-center justify-center" role="status" aria-busy="true">
      <div className="flex items-center gap-2 text-muted-foreground">
        <ShieldCheck className="size-4 animate-pulse" />
        <span className="text-sm">Redirecting…</span>
      </div>
    </div>
  );
}
