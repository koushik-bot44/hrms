'use client';

import type { ReactNode } from 'react';
import { ScrollText, UserCog, Users } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Tier 1 — Company Admin. Access enforced by RequireRole (UX) + the API guard (§6).
const nav: NavItem[] = [
  { label: 'Teams', href: '/company-admin', icon: Users },
  { label: 'Employees', href: '/company-admin/employees', icon: UserCog },
  { label: 'Audit logs', href: '/company-admin/audit', icon: ScrollText },
];

export default function CompanyAdminLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.COMPANY_ADMIN]}>
      <AppShell roleLabel="Company Admin" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}