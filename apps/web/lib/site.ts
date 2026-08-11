/**
 * The canonical public site base URL, used for absolute metadata (sitemap, robots.txt). Defaults to the
 * canonical `www` host (the apex redirects there); override with `NEXT_PUBLIC_SITE_URL` if the domain changes.
 */
export const SITE_BASE = (process.env.NEXT_PUBLIC_SITE_URL ?? 'https://www.hrorg.in').replace(/\/+$/, '');
