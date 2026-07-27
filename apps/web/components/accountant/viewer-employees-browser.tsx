'use client';

import { UserRole } from '@/lib/contract';
import { useAuth } from '@/components/auth-provider';
import { Card, CardContent } from '@/components/ui/card';
import { AccountsAdminBrowser } from '@/components/accountant/accounts-admin-browser';
import { AccountantTeamRoster } from '@/components/accountant/accountant-team-roster';

/**
 * The viewer employee browser (§2), team-wise for both read-only roles: the cross-company ACCOUNTS_ADMIN
 * drills COMPANY → TEAM → EMPLOYEE, while the team ACCOUNTANT lands directly on their own team's roster.
 * The drilldown lives in a single carded panel (the pilot's "browse" section).
 */
export function ViewerEmployeesBrowser() {
  const { session } = useAuth();
  const isAccountsAdmin = session?.type === 'USER' && session.role === UserRole.ACCOUNTS_ADMIN;
  return (
    <section className="space-y-4">
      <h2 className="text-base font-semibold tracking-tight text-foreground">
        {isAccountsAdmin ? 'Browse employees by company & team' : 'Your team'}
      </h2>
      <Card>
        <CardContent className="p-5">
          {isAccountsAdmin ? <AccountsAdminBrowser /> : <AccountantTeamRoster />}
        </CardContent>
      </Card>
    </section>
  );
}
