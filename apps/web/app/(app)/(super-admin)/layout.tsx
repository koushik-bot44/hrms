'use client';

import type { ReactNode } from 'react';
import { Building2, Fingerprint, ScrollText } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Tier 0 — Super Admin. Access enforced by RequireRole (UX) + the API guard (§6).
const nav: NavItem[] = [
  { label: 'Companies', href: '/super-admin', icon: Building2 },
  { label: 'Time & Attendance', href: '/super-admin/time-attendance', icon: Fingerprint },
  { label: 'Audit logs', href: '/super-admin/audit', icon: ScrollText },
];

export default function SuperAdminLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.SUPER_ADMIN]}>
      <AppShell roleLabel="Super Admin" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}