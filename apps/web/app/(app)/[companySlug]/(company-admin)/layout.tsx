'use client';

import type { ReactNode } from 'react';
import { ScrollText, UserCog, Users } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useCompanyPath } from '@/lib/auth/use-company-path';

// Tier 1 — Company Admin. Access enforced by RequireRole (UX) + the API guard (§6). Slugged.
export default function CompanyAdminLayout({ children }: { children: ReactNode }) {
  const cp = useCompanyPath();
  const nav: NavItem[] = [
    { label: 'Teams', href: cp('/company-admin'), icon: Users },
    { label: 'Employees', href: cp('/company-admin/employees'), icon: UserCog },
    { label: 'Audit logs', href: cp('/company-admin/audit'), icon: ScrollText },
  ];
  return (
    <RequireRole roles={[UserRole.COMPANY_ADMIN]}>
      <AppShell roleLabel="Company Admin" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
