'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, LogOut, Network } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useApiQuery } from '@/lib/api/hooks';
import { getPendingOffboarding } from '@/lib/api/offboarding';

// Cross-platform, read-only overview area (§2/§6): OVERVIEW (aggregates) + ORGANISATION (the company → team →
// people browser; §2 charter widening — names/codes/designations/roles only), plus the ONE write surface it is
// granted: OFFBOARDING — one home for the approvals inbox (its only write surface), the summary tiles and
// the full case history, badged with the live pending count.
export default function HierarchyLayout({ children }: { children: ReactNode }) {
  const { data: pending } = useApiQuery(['offboarding-pending'], getPendingOffboarding);
  const nav: NavItem[] = [
    { label: 'Overview', href: '/hierarchy', icon: LayoutDashboard },
    { label: 'Organisation', href: '/hierarchy/organisation', icon: Network },
    {
      label: 'Offboarding',
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
