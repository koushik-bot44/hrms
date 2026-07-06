'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { ShieldCheck } from 'lucide-react';
import type { Session, UserRole } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { homePathForSession } from '@/lib/auth/routes';

interface RequireRoleProps {
  /** Allowed staff roles (implies a USER session). */
  roles?: UserRole[];
  /** Restrict to a principal kind (e.g. EMPLOYEE area). */
  actor?: Session['type'];
  children: React.ReactNode;
}

function isAllowed(session: Session, roles?: UserRole[], actor?: Session['type']): boolean {
  if (actor && session.type !== actor) {
    return false;
  }
  if (roles && roles.length > 0) {
    return session.type === 'USER' && roles.includes(session.role);
  }
  return true;
}

/**
 * Client-side route protection (Phase-3 guard seam). Unauthenticated users go to the sign-in for
 * their audience (§6): the employee area sends them to /employee/login, everything else to /login.
 * Authenticated-but-out-of-scope users are bounced to their own area. The server still enforces §6 —
 * this is for UX, not security.
 */
export function RequireRole({ roles, actor, children }: RequireRoleProps) {
  const { session, status } = useAuth();
  const router = useRouter();

  const allowed = session ? isAllowed(session, roles, actor) : false;
  const signInPath = actor === 'EMPLOYEE' ? '/employee/login' : '/login';

  React.useEffect(() => {
    if (status === 'loading') {
      return;
    }
    if (status === 'unauthenticated' || !session) {
      router.replace(signInPath);
      return;
    }
    if (!allowed) {
      router.replace(homePathForSession(session));
    }
  }, [status, session, allowed, router, signInPath]);

  if (status !== 'authenticated' || !session || !allowed) {
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
