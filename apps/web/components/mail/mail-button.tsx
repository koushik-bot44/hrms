'use client';

import Link from 'next/link';
import { Mail } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { useApiQuery } from '@/lib/api/hooks';
import { getUnreadCount, mailKeys } from '@/lib/api/mail';
import { cn } from '@/lib/utils';

/**
 * The internal-mail entry point in the staff topbar (§8). Visible to every staff role, never to
 * employees (they are not in mail this stage). Shows a live unread badge from `/mail/unread-count`
 * (refetched on focus + invalidated after send/open) and opens the full mailbox at `/mail` in-session
 * (a Link, so ⌘/Ctrl-click naturally pops it into a new tab).
 */
export function MailButton() {
  const { session } = useAuth();
  const isStaff = session?.type === 'USER';

  const unread = useApiQuery(mailKeys.unread, getUnreadCount, {
    enabled: isStaff,
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  });

  if (!isStaff) return null;

  const count = unread.data?.unread ?? 0;
  const label = count > 0 ? `Mail — ${count} unread` : 'Mail';

  return (
    <Link
      href="/mail"
      aria-label={label}
      title={label}
      className={cn(
        'relative inline-flex size-9 items-center justify-center rounded-md text-muted-foreground',
        'transition-colors hover:bg-accent hover:text-accent-foreground',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
      )}
    >
      <Mail className="size-5" />
      {count > 0 ? (
        <span
          aria-hidden
          className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-semibold leading-none text-primary-foreground"
        >
          {count > 99 ? '99+' : count}
        </span>
      ) : null}
    </Link>
  );
}
