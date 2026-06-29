import type { CookieOptions } from 'express';
import type { Env } from '../config/env.validation';

/** Parse a jsonwebtoken-style duration ("15m", "7d", "3600s", "1h") into milliseconds. */
export function durationToMs(duration: string): number {
  const match = /^(\d+)\s*(ms|s|m|h|d)?$/.exec(duration.trim());
  if (!match) {
    throw new Error(`Invalid duration: "${duration}"`);
  }
  const value = Number(match[1]);
  const unit = match[2] ?? 'ms';
  const factor: Record<string, number> = {
    ms: 1,
    s: 1_000,
    m: 60_000,
    h: 3_600_000,
    d: 86_400_000,
  };
  return value * factor[unit];
}

/**
 * Options for the httpOnly refresh cookie on the API domain (§6). In production it is
 * Secure + SameSite=None for the cross-site web origin; in dev it relaxes to Lax/insecure
 * so localhost-over-http works. Scoped to `/auth` so it is only sent to refresh/logout.
 */
export function refreshCookieOptions(config: { get: (k: keyof Env, o?: unknown) => unknown }): CookieOptions {
  const isProd = config.get('NODE_ENV', { infer: true }) === 'production';
  const ttl = config.get('REFRESH_TOKEN_TTL', { infer: true }) as string;
  return {
    httpOnly: true,
    secure: isProd,
    sameSite: isProd ? 'none' : 'lax',
    path: '/auth',
    maxAge: durationToMs(ttl),
  };
}
