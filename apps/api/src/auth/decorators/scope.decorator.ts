import { SetMetadata } from '@nestjs/common';
import type { UserRole } from '@ihrms/shared';
import { SCOPE_KEY, type ScopeSpec } from '../auth.constants';

/**
 * Declares the access requirement for a route, enforced by `ScopeGuard` (§6).
 * Resource-level tenancy (companyId match, HR-owns-employee, …) is NOT here — it lives
 * in `AccessControlService`, called from handlers, so the checks stay in one place.
 */
export const Scope = (spec: ScopeSpec) => SetMetadata(SCOPE_KEY, spec);

/** Staff-only route restricted to the given roles. */
export const Roles = (...roles: UserRole[]) => Scope({ roles, actor: 'USER' });

/** Employee-only route (the onboarded subject). */
export const EmployeeOnly = () => Scope({ actor: 'EMPLOYEE' });
