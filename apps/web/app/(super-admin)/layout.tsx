'use client';

import type { ReactNode } from 'react';
import { Building2, ScrollText } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';

// Tier 0 — Super Admin. PHASE 3: the access guard mounts here. Public for now.
const nav: NavItem[] = [
  { label: 'Companies', href: '/super-admin', icon: Building2 },
  { label: 'Audit logs', href: '/super-admin/audit', icon: ScrollText },
];

export default function SuperAdminLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell roleLabel="Super Admin" nav={nav}>
      {children}
    </AppShell>
  );
}