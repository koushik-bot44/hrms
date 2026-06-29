'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, ShieldCheck, Users } from 'lucide-react';
import { UserRole } from '@ihrms/shared';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Tier 2 — HR (own onboarded employees). Access enforced by RequireRole (UX) + API guard (§6).
const nav: NavItem[] = [
  { label: 'Dashboard', href: '/hr', icon: LayoutDashboard },
  { label: 'Employees', href: '/hr/employees', icon: Users },
  { label: 'Verification', href: '/hr/verification', icon: ShieldCheck },
];

export default function HrLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.HR]}>
      <AppShell roleLabel="HR" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}