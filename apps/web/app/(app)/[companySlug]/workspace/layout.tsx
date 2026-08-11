'use client';

import type { ReactNode } from 'react';
import { usePathname } from 'next/navigation';
import { RequireRole } from '@/components/require-role';
import { WorkspaceShell } from '@/components/employee/workspace-shell';

/**
 * The employee portal (Stage 6) — where an employee who signed in with their credentials (password)
 * lands. Same EMPLOYEE principal + scope as the onboarding area; a different home. Staff are bounced by
 * the guard; the shell bounces an employee without a mailbox to the onboarding area.
 *
 * CARVE-OUT (§6): the WORKSPACE sign-in door `/{slug}/workspace/login` is PUBLIC (like the other sign-in
 * doors) — it must render for an anonymous visitor, so it sits OUTSIDE the EMPLOYEE role guard + shell.
 */
export default function WorkspaceLayout({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  if (pathname?.endsWith('/workspace/login')) {
    return <>{children}</>;
  }
  return (
    <RequireRole actor="EMPLOYEE">
      <WorkspaceShell>{children}</WorkspaceShell>
    </RequireRole>
  );
}
