import type { ReactNode } from 'react';
import { UserRole } from '@/lib/contract';
import { RequireRole } from '@/components/require-role';

/**
 * The internal mailbox (ARCHITECTURE.md §8) is its own full-page route, opened in-session from every
 * staff portal (and poppable into a new tab). Every staff role reaches it, and so does a CREDENTIALED
 * employee from the /workspace portal (§8, Stage 5) — the mailbox is portal-only for them. An
 * uncredentialed (OTP-only) employee has no mailbox and is bounced. The server enforces the same on
 * /mail/** (any authenticated account gets its own mailbox; the service 403s an uncredentialed employee).
 */
export default function MailLayout({ children }: { children: ReactNode }) {
  return (
    <RequireRole
      allowCredentialedEmployee
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
