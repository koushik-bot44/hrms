'use client';

import { UserRole } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { AccountsAdminBrowser } from '@/components/accountant/accounts-admin-browser';
import { AccountantTeamRoster } from '@/components/accountant/accountant-team-roster';

/**
 * The viewer employee browser (§2), team-wise for both read-only roles: the cross-company ACCOUNTS_ADMIN
 * drills COMPANY → TEAM → EMPLOYEE, while the team ACCOUNTANT lands directly on their own team's roster.
 */
export function ViewerEmployeesBrowser() {
  const { session } = useAuth();
  const isAccountsAdmin = session?.type === 'USER' && session.role === UserRole.ACCOUNTS_ADMIN;
  return (
    <div className="space-y-3">
      <h2 className="text-sm font-semibold text-muted-foreground">
        {isAccountsAdmin ? 'Browse employees by company & team' : 'Your team'}
      </h2>
      {isAccountsAdmin ? <AccountsAdminBrowser /> : <AccountantTeamRoster />}
    </div>
  );
}
