import type { ReactNode } from 'react';
import { RequireRole } from '@/components/require-role';
import { WorkspaceShell } from '@/components/employee/workspace-shell';

/**
 * The employee portal (Stage 6) — where an employee who signed in with their credentials (password)
 * lands. Same EMPLOYEE principal + scope as the onboarding area; a different home. Staff are bounced by
 * the guard; the shell bounces an employee without a mailbox to the onboarding area.
 */
export default function WorkspaceLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole actor="EMPLOYEE">
      <WorkspaceShell>{children}</WorkspaceShell>
    </RequireRole>
  );
}
