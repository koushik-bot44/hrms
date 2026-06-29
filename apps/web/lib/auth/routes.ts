import { UserRole, type Session } from '@ihrms/shared';

/** The landing path for a session — used after login and to bounce out-of-scope users. */
export function homePathForSession(session: Session): string {
  if (session.type === 'EMPLOYEE') {
    return '/employee';
  }
  switch (session.role) {
    case UserRole.SUPER_ADMIN:
      return '/super-admin';
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
