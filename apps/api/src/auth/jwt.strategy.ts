import { Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { PassportStrategy } from '@nestjs/passport';
import { ExtractJwt, Strategy } from 'passport-jwt';
import type { Env } from '../config/env.validation';
import { type AccessClaims, type Principal, principalFromAccessClaims } from './principal';

/**
 * Validates the short-lived access token from `Authorization: Bearer`. Refresh tokens
 * are signed with a different secret and are NOT accepted here. The verified claims are
 * turned into a `Principal` and attached to `req.user`.
 */
@Injectable()
export class JwtStrategy extends PassportStrategy(Strategy, 'jwt') {
  constructor(config: ConfigService<Env, true>) {
    super({
      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
      ignoreExpiration: false,
      secretOrKey: config.get('JWT_SECRET', { infer: true }),
    });
  }

  validate(payload: AccessClaims): Principal {
    const principal = principalFromAccessClaims(payload);
    if (!principal) {
      throw new UnauthorizedException('Invalid token');
    }
    return principal;
  }
}
