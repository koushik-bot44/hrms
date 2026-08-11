import type { MetadataRoute } from 'next';
import { SITE_BASE } from '@/lib/site';

/**
 * XML sitemap (Next serves it at `/sitemap.xml`) listing the PUBLIC marketing pages. The app / sign-in routes
 * are deliberately excluded — they sit behind authentication and aren't indexable content. Referenced from
 * `robots.txt` so search engines discover it.
 */
export default function sitemap(): MetadataRoute.Sitemap {
  const lastModified = new Date();
  const pages: {
    path: string;
    priority: number;
    changeFrequency: MetadataRoute.Sitemap[number]['changeFrequency'];
  }[] = [
    { path: '/', priority: 1.0, changeFrequency: 'weekly' },
    { path: '/features', priority: 0.8, changeFrequency: 'monthly' },
    { path: '/security', priority: 0.8, changeFrequency: 'monthly' },
    { path: '/support', priority: 0.7, changeFrequency: 'monthly' },
    { path: '/contact', priority: 0.7, changeFrequency: 'monthly' },
  ];
  return pages.map(({ path, priority, changeFrequency }) => ({
    url: `${SITE_BASE}${path}`,
    lastModified,
    changeFrequency,
    priority,
  }));
}
