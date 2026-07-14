'use client';

import type { ReactNode } from 'react';
import { Bell, CalendarDays, ClipboardCheck, Clock } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';

// Tier 2 — Manager (own team). Access enforced by RequireRole (UX) + the API guard (§6).
const nav: NavItem[] = [
  { label: 'Approvals', href: '/manager', icon: ClipboardCheck },
  { label: 'Attendance', href: '/manager/attendance', icon: Clock },
  { label: 'Leave', href: '/manager/leave', icon: CalendarDays },
  { label: 'Notifications', href: '/manager/notifications', icon: Bell },
];

export default function ManagerLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole roles={[UserRole.MANAGER]}>
      <AppShell roleLabel="Manager" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}