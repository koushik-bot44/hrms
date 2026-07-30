import { UserRole, type Session } from '@/lib/contract';
import { buildCompanyPath } from '@/lib/company-url';

/**
 * The landing path for a session (Stage 2 — tenant-scoped URLs). Company-scoped areas live under the
 * company's slug (`/{companySlug}/…`); platform roles stay top-level. Same EMPLOYEE principal, two homes
 * (Stage 6): a PASSWORD (credentialed) sign-in lands in the employee PORTAL, an OTP sign-in in the
 * ONBOARDING area — both slugged. A company-scoped session with no slug (shouldn't happen for an
 * authenticated principal) falls back to /login.
 */
export function homePathForSession(session: Session): string {
  const slug = session.companySlug;
  if (session.type === 'EMPLOYEE') {
    const area = session.authMethod === 'PASSWORD' ? 'workspace' : 'employee';
    return slug ? buildCompanyPath(slug, `/${area}`) : '/login';
  }
  switch (session.role) {
    case UserRole.SUPER_ADMIN:
      return '/super-admin';
    // The cross-company Accounts Admin is a PLATFORM role — top-level /accounts (the team-scoped
    // Accountant is slugged, below). Both mount the same read-only screens (§2/§6).
    case UserRole.ACCOUNTS_ADMIN:
      return '/accounts';
    // Cross-platform, read-only, aggregates-only overview (§2).
    case UserRole.HIERARCHY:
      return '/hierarchy';
    // Company-scoped roles — under the tenant slug.
    case UserRole.ACCOUNTANT:
      return slug ? buildCompanyPath(slug, '/accountant') : '/login';
    case UserRole.COMPANY_ADMIN:
      return slug ? buildCompanyPath(slug, '/company-admin') : '/login';
    case UserRole.HR:
      return slug ? buildCompanyPath(slug, '/hr') : '/login';
    case UserRole.MANAGER:
      return slug ? buildCompanyPath(slug, '/manager') : '/login';
    default:
      return '/login';
  }
}
