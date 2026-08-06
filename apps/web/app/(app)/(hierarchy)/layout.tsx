'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, LogOut } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useApiQuery } from '@/lib/api/hooks';
import { getPendingOffboarding } from '@/lib/api/offboarding';

// Cross-platform, read-only, AGGREGATES-ONLY overview area (§2/§6), plus the ONE write surface it is granted:
// offboarding approvals (§Offboarding) — a minimal-PII inbox, badged with the live pending count.
export default function HierarchyLayout({ children }: { children: ReactNode }) {
  const { data: pending } = useApiQuery(['offboarding-pending'], getPendingOffboarding);
  const nav: NavItem[] = [
    { label: 'Overview', href: '/hierarchy', icon: LayoutDashboard },
    {
      label: 'Offboarding approvals',
      href: '/hierarchy/offboarding',
      icon: LogOut,
      badge: pending?.length || undefined,
    },
  ];

  return (
    <RequireRole roles={[UserRole.HIERARCHY]}>
      <AppShell roleLabel="Platform" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
