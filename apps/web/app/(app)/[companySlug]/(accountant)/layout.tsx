'use client';

import type { ReactNode } from 'react';
import { FileText, LayoutDashboard, ScrollText } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useCompanyPath } from '@/lib/auth/use-company-path';

/**
 * The TEAM-scoped Accountant area (§2/§6) — slugged (`/{companySlug}/accountant`). The cross-company
 * Accounts Admin is a PLATFORM role with its own top-level `/accounts` mount; both render the same shared
 * read-only viewer screens. Requests (§8d) is ACCOUNTANT-only (the Accounts Admin has no team).
 */
export default function AccountantLayout({ children }: { children: ReactNode }) {
  const cp = useCompanyPath();
  const nav: NavItem[] = [
    { label: 'Overview', href: cp('/accountant'), icon: LayoutDashboard },
    { label: 'Approval audit', href: cp('/accountant/audit'), icon: ScrollText },
    { label: 'Requests', href: cp('/accountant/requests'), icon: FileText },
  ];

  return (
    <RequireRole roles={[UserRole.ACCOUNTANT]}>
      <AppShell roleLabel="Accounts" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
