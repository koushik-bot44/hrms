'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, ScrollText } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Tier 0 (read-only) — Accountant: cross-company viewer of approved employees + approval audit (§2/§6).
const nav: NavItem[] = [
  { label: 'Overview', href: '/accountant', icon: LayoutDashboard },
  { label: 'Approval audit', href: '/accountant/audit', icon: ScrollText },
];

export default function AccountantLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.ACCOUNTANT]}>
      <AppShell roleLabel="Accountant" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
