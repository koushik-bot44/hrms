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
// never produces a double slash.
const BASE_URL = (process.env.NEXT_PUBLIC_API_URL ?? '').replace(/\/+$/, '');

export function apiBaseUrl(): string {
  return BASE_URL;
}

export interface ApiRequestOptions<T> extends Omit<RequestInit, 'body'> {
  /** JSON-serializable request body. */
  body?: unknown;
  /** Optional zod schema from @cdpp/shared used to parse/validate the response. */
  schema?: z.ZodType<T>;
}

/**
 * Thin fetch wrapper. JSON in / JSON out, credentials included, types come from
 * @cdpp/shared (NOT codegen). Throws {@link ApiError} on non-2xx; when a `schema`
 * is supplied the response is parsed through it so the return type is guaranteed.
 */
export async function apiFetch<T = unknown>(
  path: string,
  options: ApiRequestOptions<T> = {},
): Promise<T> {
  const { schema, body, headers, ...init } = options;
  const url = `${BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;

  let res: Response;
  try {
    res = await fetch(url, {
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
      ...init,
    });
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
