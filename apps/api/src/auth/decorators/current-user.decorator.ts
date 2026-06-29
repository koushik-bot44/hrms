import { createParamDecorator, type ExecutionContext } from '@nestjs/common';
import type { Principal } from '../principal';

/** Injects the authenticated `Principal` (set on `req.user` by the JWT strategy). */
export const CurrentUser = createParamDecorator(
  (_data: unknown, ctx: ExecutionContext): Principal | undefined => {
    return ctx.switchToHttp().getRequest<{ user?: Principal }>().user;
  },
);
