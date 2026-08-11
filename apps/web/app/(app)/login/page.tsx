import { StaffLoginScreen } from '@/components/auth/staff-login-screen';

/**
 * Door 1 — the ONE staff sign-in (§6, two-door consolidation): ALL staff, company-scoped AND platform
 * (HR, Company Admin, Manager, Accountant + Super Admin, Hierarchy, Accounts Admin), sign in here with
 * email + password. Sends `audience="STAFF"`, so a credentialed EMPLOYEE is refused (PORTAL_MISMATCH) and
 * cross-linked to the employee door. The company slug is applied only after sign-in (homePathForSession).
 * Thin mount over the shared screen.
 */
export default function LoginPage() {
  return <StaffLoginScreen audience="STAFF" />;
}
