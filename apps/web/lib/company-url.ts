/**
 * Human-readable company URL segments. The address bar shows the company NAME (as a slug) followed by
 * the stable company id — e.g. `acme-corporation-cmrc3rvj6cdfsf95c3jpb`. The id is what actually resolves
 * the company: names are neither unique nor immutable (they can repeat and be renamed), so they can't
 * identify a company on their own. cuids contain no "-", so the id is always the segment after the final
 * dash. Old id-only links (`.../companies/cmrc3rvj6...`) keep resolving unchanged.
 */

/** A URL-safe slug of a company name (lowercase; non-alphanumeric runs collapse to a single dash). */
export function slugifyCompanyName(name: string): string {
  const slug = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug || 'company';
}

/** The company URL segment: `{name-slug}-{id}` (name visible, id resolvable). */
export function companyParam(name: string, id: string): string {
  return `${slugifyCompanyName(name)}-${id}`;
}

/** Recover the real company id from a `{slug}-{id}` (or bare `{id}`) URL segment. */
export function companyIdFromParam(param: string): string {
  const i = param.lastIndexOf('-');
  return i === -1 ? param : param.slice(i + 1);
}

/**
 * Prefix a company-scoped sub-path with the tenant slug (Stage 2 routing):
 * {@code buildCompanyPath('acme', '/hr/employees') === '/acme/hr/employees'}. The single builder for
 * every slugged link/redirect so the slug is never string-interpolated ad hoc.
 */
export function buildCompanyPath(slug: string, subpath = ''): string {
  const clean = subpath === '' || subpath.startsWith('/') ? subpath : `/${subpath}`;
  return `/${slug}${clean}`;
}
