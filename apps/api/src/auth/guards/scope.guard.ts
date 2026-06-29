import {
  type CanActivate,
  type ExecutionContext,
  ForbiddenException,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { IS_PUBLIC_KEY, SCOPE_KEY, type ScopeSpec } from '../auth.constants';
import type { Principal } from '../principal';

/**
 * Enforces the declarative `@Scope()` requirement (role / actor kind) after
 * authentication. Returns true for `@Public()` routes; requires an authenticated
 * principal otherwise. Resource-level tenancy is enforced separately by
 * `AccessControlService`.
 */
@Injectable()
export class ScopeGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const targets = [context.getHandler(), context.getClass()];

    const isPublic = this.reflector.getAllAndOverride<boolean>(IS_PUBLIC_KEY, targets);
    if (isPublic) {
      return true;
    }

    const principal = context.switchToHttp().getRequest<{ user?: Principal }>().user;
    if (!principal) {
      throw new UnauthorizedException('Authentication required');
    }

    const scope = this.reflector.getAllAndOverride<ScopeSpec>(SCOPE_KEY, targets);
    if (!scope) {
      // Authenticated is enough when no @Scope is declared.
      return true;
    }

    if (scope.actor && scope.actor !== 'ANY' && principal.type !== scope.actor) {
      throw new ForbiddenException('Not permitted for this account type');
    }

    if (scope.roles && scope.roles.length > 0) {
      if (principal.type !== 'USER' || !scope.roles.includes(principal.role)) {
        throw new ForbiddenException('Insufficient role');
      }
    }

    return true;
  }
}
