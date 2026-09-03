import type { z } from 'zod';

/**
 * Typed error thrown for any non-2xx response (or a network failure, with
 * `status === 0`). Carries the parsed error body for callers that want detail.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, message: string, body: unknown = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

// Base URL of the API, with any trailing slash(es) stripped so `${BASE}${path}`
// never produces a double slash. Defaults to the same-origin `/api` proxy (see
// next.config.js) so the refresh cookie stays first-party; local dev overrides it with
// NEXT_PUBLIC_API_URL pointing straight at the dev API.
const BASE_URL = (process.env.NEXT_PUBLIC_API_URL ?? '/api').replace(/\/+$/, '');

export function apiBaseUrl(): string {
  return BASE_URL;
}

/**
 * Auth bridge set by the AuthProvider: how to read the in-memory access token and how
 * to silently refresh it. Kept here (not React state) so the fetch layer can attach the
 * token and recover from a 401 without importing React.
 */
export interface AuthHooks {
  getToken: () => string | null;
  refresh: () => Promise<string | null>;
}

let authHooks: AuthHooks | null = null;
export function setAuthHooks(hooks: AuthHooks | null): void {
  authHooks = hooks;
}

export interface ApiRequestOptions<T> extends Omit<RequestInit, 'body'> {
  /** JSON-serializable request body. */
  body?: unknown;
  /** Optional zod schema from @/lib/contract used to parse/validate the response. */
  schema?: z.ZodType<T>;
  /** Skip the access token + the 401->refresh->retry (used by the auth endpoints). */
  skipAuth?: boolean;
}

/**
 * Thin fetch wrapper. JSON in / JSON out, credentials included, types come from
 * @/lib/contract (NOT codegen). Attaches the access token and, on a 401, refreshes once
 * and retries. Throws {@link ApiError} on non-2xx; a `schema` guarantees the return type.
 */
export async function apiFetch<T = unknown>(
  path: string,
  options: ApiRequestOptions<T> = {},
): Promise<T> {
  const { schema, body, headers, skipAuth, ...init } = options;
  const url = `${BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;

  const run = (token: string | null): Promise<Response> =>
    fetch(url, {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      ...init,
    });

  let res: Response;
  try {
    res = await run(skipAuth ? null : authHooks?.getToken() ?? null);
    if (res.status === 401 && !skipAuth && authHooks) {
      const refreshed = await authHooks.refresh();
      if (refreshed) {
        res = await run(refreshed);
      }
    }
  } catch (cause) {
    throw new ApiError(0, `Network error reaching ${url}`, cause);
  }

  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    const message =
      data && typeof data === 'object' && 'message' in data
        ? String((data as Record<string, unknown>).message)
        : `Request failed with status ${res.status}`;
    throw new ApiError(res.status, message, data);
  }

  return schema ? schema.parse(data) : (data as T);
}
