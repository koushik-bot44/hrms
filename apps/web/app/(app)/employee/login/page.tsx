import { EmployeeLoginScreen } from '@/components/auth/employee-login-screen';

/**
 * Top-level employee sign-in (§6) — RETAINED indefinitely so legacy invite links (`/employee/login?email=…`)
 * already in inboxes keep working. Stage-3 invites now land at `/{slug}/employee/login?email=…` (same screen).
 * Thin mount over the shared screen.
 */
export default function EmployeeLoginPage() {
  return <EmployeeLoginScreen />;
}
