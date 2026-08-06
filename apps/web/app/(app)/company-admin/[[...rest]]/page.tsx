import { LegacyRedirect } from '@/components/legacy-redirect';

/** Legacy /company-admin[/…] → Stage-2 slugged home (transition safety). */
export default function LegacyCompanyAdminRedirect() {
  return <LegacyRedirect />;
}
