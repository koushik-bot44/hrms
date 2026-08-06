import { LegacyRedirect } from '@/components/legacy-redirect';

/** Legacy /accountant[/…] → Stage-2 home: ACCOUNTANT → /{slug}/accountant, ACCOUNTS_ADMIN → /accounts. */
export default function LegacyAccountantRedirect() {
  return <LegacyRedirect />;
}
