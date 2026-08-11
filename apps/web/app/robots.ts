import type { MetadataRoute } from 'next';
import { SITE_BASE } from '@/lib/site';

/**
 * robots.txt (Next serves it at `/robots.txt`) — there was none before (the request 404'd). Allows crawling
 * of the public site and points crawlers at the sitemap. The sign-in / app areas aren't linked publicly, so
 * there's nothing to disallow here.
 */
export default function robots(): MetadataRoute.Robots {
  return {
    rules: { userAgent: '*', allow: '/' },
    sitemap: `${SITE_BASE}/sitemap.xml`,
  };
}
