import { StaffLoginScreen } from '@/components/auth/staff-login-screen';

/**
 * Top-level staff sign-in (§6) — RETAINED indefinitely for platform roles (Super Admin / Accounts Admin /
 * Hierarchy, who have no company slug) and any legacy `/login` links. Company staff also reach the same door
 * slugged at `/{slug}/login` (Stage 3). Thin mount over the shared screen.
 */
export default function LoginPage() {
  return <StaffLoginScreen />;
}
