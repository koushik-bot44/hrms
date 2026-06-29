'use client';

import type { ReactNode } from 'react';
import { UserRound } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Tier 3 — Employee (own record only). Access enforced by RequireRole (UX) + the API guard (§6).
const nav: NavItem[] = [{ label: 'My profile', href: '/employee', icon: UserRound }];

export default function EmployeeLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole actor="EMPLOYEE">
      <AppShell roleLabel="Employee" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}