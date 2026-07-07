import { UserRole, type Session } from '@/lib/contract';

/** The landing path for a session — used after login and to bounce out-of-scope users. */
export function homePathForSession(session: Session): string {
  if (session.type === 'EMPLOYEE') {
    return '/employee';
  }
  switch (session.role) {
    case UserRole.SUPER_ADMIN:
      return '/super-admin';
    case UserRole.ACCOUNTANT:
      return '/accountant';
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
