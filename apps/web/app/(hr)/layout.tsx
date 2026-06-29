'use client';

import type { ReactNode } from 'react';
import { LayoutDashboard, ShieldCheck, Users } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';

// Tier 2 — HR. PHASE 3: access guard mounts here (own onboarded employees). Public for now.
const nav: NavItem[] = [
  { label: 'Dashboard', href: '/hr', icon: LayoutDashboard },
  { label: 'Employees', href: '/hr/employees', icon: Users },
  { label: 'Verification', href: '/hr/verification', icon: ShieldCheck },
];

export default function HrLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell roleLabel="HR" nav={nav}>
      {children}
    </AppShell>
  );
}