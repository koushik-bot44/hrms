'use client';

import type { ReactNode } from 'react';
import { Bell, CalendarOff, ClipboardCheck, Clock } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useCompanyPath } from '@/lib/auth/use-company-path';

// Tier 2 — Manager (own team). Access enforced by RequireRole (UX) + the API guard (§6). Slugged.
export default function ManagerLayout({ children }: { children: ReactNode }) {
  const cp = useCompanyPath();
  const nav: NavItem[] = [
    { label: 'Team onboarding', href: cp('/manager'), icon: ClipboardCheck },
    { label: 'Attendance', href: cp('/manager/attendance'), icon: Clock },
    { label: 'Leave', href: cp('/manager/leave'), icon: CalendarOff },
    { label: 'Notifications', href: cp('/manager/notifications'), icon: Bell },
  ];
  return (
    <RequireRole roles={[UserRole.MANAGER]}>
      <AppShell roleLabel="Manager" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
