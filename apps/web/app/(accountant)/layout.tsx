'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, ScrollText } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Read-only viewer area shared by the cross-company Accounts Admin and the team-scoped Accountant
// (§2/§6). The API scopes every read by the signed-in role; the UI is the same.
const nav: NavItem[] = [
  { label: 'Overview', href: '/accountant', icon: LayoutDashboard },
  { label: 'Approval audit', href: '/accountant/audit', icon: ScrollText },
];

export default function AccountantLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.ACCOUNTS_ADMIN, UserRole.ACCOUNTANT]}>
      <AppShell roleLabel="Accounts" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
