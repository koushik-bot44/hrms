'use client';

import type { ReactNode } from 'react';
import { ScrollText, Users } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';

// Tier 1 — Company Admin. PHASE 3: access guard mounts here. Public for now.
const nav: NavItem[] = [
  { label: 'Teams', href: '/company-admin', icon: Users },
  { label: 'Audit logs', href: '/company-admin/audit', icon: ScrollText },
];

export default function CompanyAdminLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell roleLabel="Company Admin" nav={nav}>
      {children}
    </AppShell>
  );
}