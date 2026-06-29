'use client';

import type { ReactNode } from 'react';
import { Bell, ClipboardCheck } from 'lucide-react';
import { AppShell, type NavItem } from '@/components/app-shell';

// Tier 2 — Manager. PHASE 3: access guard mounts here (own team). Public for now.
const nav: NavItem[] = [
  { label: 'Approvals', href: '/manager', icon: ClipboardCheck },
  { label: 'Notifications', href: '/manager/notifications', icon: Bell },
];

export default function ManagerLayout({ children }: { children: ReactNode }) {
  return (
    <AppShell roleLabel="Manager" nav={nav}>
      {children}
    </AppShell>
  );
}