import type { ReactNode } from 'react';
import { UserRole } from '@/lib/contract';
import { RequireRole } from '@/components/require-role';

/**
 * The internal mailbox (ARCHITECTURE.md §8) is its own full-page route, opened in-session from every
 * staff portal (and poppable into a new tab). Every staff role reaches it; employees are not in mail,
 * so they are bounced by the guard. The server enforces the same rule on /mail/**.
 */
export default function MailLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole
      roles={[
        UserRole.SUPER_ADMIN,
        UserRole.ACCOUNTS_ADMIN,
        UserRole.COMPANY_ADMIN,
        UserRole.HR,
        UserRole.MANAGER,
        UserRole.ACCOUNTANT,
      ]}
    >
      {children}
    </RequireRole>
  );
}
