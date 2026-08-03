/**
 * The "Generate" signature styles — a label + the CSS variable holding the self-hosted script family name.
 * Kept separate from `app/fonts.ts` (which instantiates the next/font faces) so client components can read
 * the plain descriptors without pulling the font pipeline into their bundle. The var names must match the
 * `variable:` values declared in `app/fonts.ts`.
 */
export const SIGNATURE_STYLES = [
  { id: 'flowing', label: 'Flowing', cssVar: '--font-sig-flowing' },
  { id: 'elegant', label: 'Elegant', cssVar: '--font-sig-elegant' },
  { id: 'casual', label: 'Casual', cssVar: '--font-sig-casual' },
] as const;

export type SignatureStyleId = (typeof SIGNATURE_STYLES)[number]['id'];
