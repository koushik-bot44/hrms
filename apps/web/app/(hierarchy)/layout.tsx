'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Cross-platform, read-only, AGGREGATES-ONLY overview area (§2/§6). Data views land later; this stage
// is the shell + a placeholder overview.
const nav: NavItem[] = [{ label: 'Overview', href: '/hierarchy', icon: LayoutDashboard }];

export default function HierarchyLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.HIERARCHY]}>
      <AppShell roleLabel="Platform" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
