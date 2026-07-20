'use client';

import type { ReactNode } from 'react';
import { FileText, LayoutDashboard, ScrollText } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useAuth } from '@/components/auth-provider';

// Read-only viewer area shared by the cross-company Accounts Admin and the team-scoped Accountant
// (§2/§6). The API scopes every read by the signed-in role; the UI is the same.
const nav: NavItem[] = [
  { label: 'Overview', href: '/accountant', icon: LayoutDashboard },
  { label: 'Approval audit', href: '/accountant/audit', icon: ScrollText },
];

// Requests is the team-scoped Accountant's fulfilment inbox (§8d) — the cross-company Accounts Admin
// has no team, so the item (and the endpoint) are ACCOUNTANT-only.
const requestsItem: NavItem = { label: 'Requests', href: '/accountant/requests', icon: FileText };

export default function AccountantLayout({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const isAccountant = session?.type === 'USER' && session.role === UserRole.ACCOUNTANT;
  const items = isAccountant ? [...nav, requestsItem] : nav;

  return (
    <RequireRole roles={[UserRole.ACCOUNTS_ADMIN, UserRole.ACCOUNTANT]}>
      <AppShell roleLabel="Accounts" nav={items}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
