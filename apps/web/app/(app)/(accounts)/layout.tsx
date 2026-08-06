'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, ScrollText } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

/**
 * The PLATFORM Accounts Admin area (§2/§6) — top-level `/accounts` (cross-company, no team). It renders
 * the SAME shared read-only viewer screens as the team Accountant's slugged `/{companySlug}/accountant`
 * mount; only the guard (ACCOUNTS_ADMIN) and nav differ. No Requests (that is the team Accountant's).
 */
const nav: NavItem[] = [
  { label: 'Overview', href: '/accounts', icon: LayoutDashboard },
  { label: 'Approval audit', href: '/accounts/audit', icon: ScrollText },
];

export default function AccountsLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.ACCOUNTS_ADMIN]}>
      <AppShell roleLabel="Accounts" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
