import { LegacyRedirect } from '@/components/legacy-redirect';

/** Legacy /employee[/…] onboarding → Stage-2 slugged home. Static /employee/login (sign-in) wins over
 *  this optional catch-all, so the unauthenticated login page is unaffected. */
export default function LegacyEmployeeRedirect() {
  return <LegacyRedirect />;
}
