'use client';

import type { ReactNode } from 'react';
import { UserRound } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';

// Tier 3 — Employee. PHASE 3: access guard mounts here (own record only). Public for now.
const nav: NavItem[] = [{ label: 'My profile', href: '/employee', icon: UserRound }];

export default function EmployeeLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell roleLabel="Employee" nav={nav}>
      {children}
    </AppShell>
  );
}