'use client';

import type { ReactNode } from 'react';
import { Inbox, LayoutDashboard, ShieldCheck, Users } from 'lucide-react';
import { UserRole } from '@/lib/contract';
import { AppShell, type NavItem } from '@/components/app-shell';
import { RequireRole } from '@/components/require-role';
import { useApiQuery } from '@/lib/api/hooks';
import { getHrLetterRequests, requestKeys } from '@/lib/api/requests';
import { useCompanyPath } from '@/lib/auth/use-company-path';

// Tier 2 — HR (own onboarded employees). Access enforced by RequireRole (UX) + API guard (§6). Slugged.
export default function HrLayout({ children }: { children: ReactNode }) {
  const cp = useCompanyPath();
  // The Requests nav badge (§3.6) — open letter requests routed to this HR (mirrors the hierarchy pending badge).
  const { data: letterRequests } = useApiQuery(requestKeys.hrLetters(), getHrLetterRequests);
  const nav: NavItem[] = [
    { label: 'Dashboard', href: cp('/hr'), icon: LayoutDashboard },
    { label: 'Employees', href: cp('/hr/employees'), icon: Users },
    { label: 'Look up employee', href: cp('/hr/verification'), icon: ShieldCheck },
    { label: 'Requests', href: cp('/hr/requests'), icon: Inbox, badge: letterRequests?.pendingCount || undefined },
  ];
  return (
    <RequireRole roles={[UserRole.HR]}>
      <AppShell roleLabel="HR" nav={nav}>
        {children}
      </AppShell>
    </RequireRole>
  );
}
