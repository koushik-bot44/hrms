'use client';

import type { ReactNode } from 'react';
import { UserRound } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useCompanyPath } from '@/lib/auth/use-company-path';

// Tier 3 — Employee onboarding (own record only). Access enforced by RequireRole (UX) + API guard (§6). Slugged.
export default function EmployeeLayout({ children }: { children: ReactNode }) {
  const cp = useCompanyPath();
  const nav: NavItem[] = [{ label: 'My profile', href: cp('/employee'), icon: UserRound }];
  return (
    <RequireRole actor="EMPLOYEE">
      {/* Onboarding area — the mailbox lives in the employee portal (/workspace), not here. */}
      <AppShell roleLabel="Employee" nav={nav} showMail={false}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
