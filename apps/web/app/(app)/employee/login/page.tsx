import { StaffLoginScreen } from '@/components/auth/staff-login-screen';

/**
 * Door 2 — the workspace sign-in for APPROVED, credentialed EMPLOYEES (§6, two-door consolidation): email +
 * workspace password. Sends `audience="WORKSPACE"`, so a staff USER is refused (PORTAL_MISMATCH) and
 * cross-linked to the staff door. Thin mount over the SAME shared credential screen as door 1.
 *
 * NOTE: this path previously hosted the onboarding OTP door; that door is invite-link-only and now lives
 * solely at the slugged `/{slug}/employee/login?token=…` (the invite links already point there, unchanged).
 */
export default function EmployeeLoginPage() {
  return <StaffLoginScreen audience="WORKSPACE" />;
}
