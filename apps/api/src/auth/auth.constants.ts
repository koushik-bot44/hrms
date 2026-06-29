import type { UserRole } from '@ihrms/shared';

/** Metadata keys for the auth decorators. */
export const IS_PUBLIC_KEY = 'ihrms:isPublic';
export const SCOPE_KEY = 'ihrms:scope';

export type ActorKind = 'USER' | 'EMPLOYEE';

/**
 * Declarative access requirement read by `ScopeGuard`. `roles` (implies actor USER)
 * gates staff routes; `actor` gates which principal kind may enter. Fine-grained
 * resource/tenancy checks live in `AccessControlService`, not here.
 */
export interface ScopeSpec {
  roles?: UserRole[];
  actor?: ActorKind | 'ANY';
}
