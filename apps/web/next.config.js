/** @type {import('next').NextConfig} */

// Same-origin API proxy. When API_ORIGIN is set (production), every request the browser
// makes to `/api/*` is reverse-proxied (server-side) to the real API, so the browser only
// ever talks to THIS origin. That makes the API's httpOnly refresh cookie a FIRST-PARTY
// cookie on the web domain — which mobile browsers keep across an app close. Without the
// proxy the web (vercel.app) and API (railway.app) are different sites, so the refresh
// cookie is cross-site (SameSite=None); Safari/Chrome and installed PWAs purge cross-site
// cookies when the app closes, which logged the user out on reopen.
//
// API_ORIGIN is server-only (not NEXT_PUBLIC_*) — it is the proxy destination, never shipped
// to the browser. The browser-side base URL is NEXT_PUBLIC_API_URL, which is set to `/api`
// in production so calls flow through this proxy. Locally the proxy is off (API_ORIGIN unset)
// and the web talks to the dev API directly (same-site localhost, so cookies persist anyway).
const apiOrigin = (process.env.API_ORIGIN ?? '').replace(/\/+$/, '');

const nextConfig = {
  reactStrictMode: true,
  async rewrites() {
    if (!apiOrigin) return [];
    return [{ source: '/api/:path*', destination: `${apiOrigin}/:path*` }];
  },
};

module.exports = nextConfig;
