'use client';

import * as React from 'react';
import { useRouter } from 'next/navigation';
import { CalendarDays, Clock, FileText, Inbox } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { AppShell, type NavItem } from '@/components/app-shell';
import { WorkspaceEntryGuard } from '@/components/attendance/workspace-entry-guard';

/**
 * The employee PORTAL shell (Stage 6) — mirrors the staff `AppShell` (topbar + sidebar + Mail button)
 * with its own growable nav. A credentialed EMPLOYEE reaches this; one without a mailbox is bounced to
 * the onboarding area.
 */
const nav: NavItem[] = [
  { label: 'Mailbox', href: '/mail', icon: Inbox },
  { label: 'Attendance', href: '/workspace/attendance', icon: Clock },
  { label: 'Leave', href: '/workspace/leave', icon: CalendarDays },
  // HR side is future — only the Accounts side is wired today (§8d).
  { label: 'HR/Accounts Requests', href: '/workspace/requests', icon: FileText },
];

export function WorkspaceShell({ children }: { children: React.ReactNode }) {
  const { session } = useAuth();
  const router = useRouter();
  // The portal is for credentialed employees; an employee without a mailbox belongs in onboarding.
  const noMailbox = session?.type === 'EMPLOYEE' && !session.mailAddress;

  React.useEffect(() => {
    if (noMailbox) router.replace('/employee');
  }, [noMailbox, router]);

  if (noMailbox) return null;

  return (
    <AppShell roleLabel="Employee" nav={nav}>
      {/* Server-state-driven clock-in / return-from-break prompts (§8a v2), evaluated on entry + focus. */}
      <WorkspaceEntryGuard />
      {children}
    </AppShell>
  );
}
