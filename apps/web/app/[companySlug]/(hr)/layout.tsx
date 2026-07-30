'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, ShieldCheck, Users } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useCompanyPath } from '@/lib/auth/use-company-path';

// Tier 2 — HR (own onboarded employees). Access enforced by RequireRole (UX) + API guard (§6). Slugged.
export default function HrLayout({ children }: { children: ReactNode }) {
  const cp = useCompanyPath();
  const nav: NavItem[] = [
    { label: 'Dashboard', href: cp('/hr'), icon: LayoutDashboard },
    { label: 'Employees', href: cp('/hr/employees'), icon: Users },
    { label: 'Look up employee', href: cp('/hr/verification'), icon: ShieldCheck },
  ];
  return (
    <RequireRole roles={[UserRole.HR]}>
      <AppShell roleLabel="HR" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
