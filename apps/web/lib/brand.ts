/**
 * The hrorg.in brand identity — the ONE source of truth for the brand name + subtitle, so the exact wording
 * AND its Title Case can never drift again. Every render point (marketing nav/footer, the auth panels) and
 * every piece of metadata (page titles, OG/Twitter titles + alt) imports from here; the composed
 * public/brand/og-image.png is regenerated from the same text. Plain constants → usable in both server and
 * client components.
 */
export const BRAND_NAME = 'hrorg.in';
export const BRAND_SUBTITLE = 'Integrated HR Management Services';
/** The full brand line used in <title>/OG titles + the OG image alt. */
export const BRAND_TITLE = `${BRAND_NAME} — ${BRAND_SUBTITLE}`;
