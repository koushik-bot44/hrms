import { UserRole, type Session } from '@/lib/contract';

/**
 * The landing path for a session — used after login and to bounce out-of-scope users. Same EMPLOYEE
 * principal, two homes (Stage 6): signing in with credentials (PASSWORD) lands in the employee PORTAL;
 * signing in with name + email + OTP lands in the ONBOARDING area (also the default when unknown).
 */
export function homePathForSession(session: Session): string {
  if (session.type === 'EMPLOYEE') {
    return session.authMethod === 'PASSWORD' ? '/workspace' : '/employee';
  }
  switch (session.role) {
    case UserRole.SUPER_ADMIN:
      return '/super-admin';
    // Both the cross-company Accounts Admin and the team-scoped Accountant use the same read-only area.
    case UserRole.ACCOUNTS_ADMIN:
    case UserRole.ACCOUNTANT:
      return '/accountant';
    // Cross-platform, read-only, aggregates-only overview (§2).
    case UserRole.HIERARCHY:
      return '/hierarchy';
    case UserRole.COMPANY_ADMIN:
      return '/company-admin';
    case UserRole.HR:
      return '/hr';
    case UserRole.MANAGER:
      return '/manager';
    default:
      return '/login';
  }
}
