/**
 * Inner-surface convention (design system). A LIGHTER treatment than {@link Card} for surfaces that live
 * INSIDE a Card or page — list rows, chips, dropdowns, inner panels. Unlike Card it is not a floating
 * container: no p-7, no rounded-2xl, no hover lift. It is a documented utility-class combo (rather than a
 * component) because these surfaces are heterogeneous element types — `<button>` chips, `<ul>` dropdowns,
 * `<li>`/`<div>` panels — so a wrapper component would be awkward; `cn(surface(v), '…layout…')` composes
 * cleanly onto any element and `tailwind-merge` lets a call site override the radius (e.g. `rounded-lg`
 * on a small chip). All colours are tokens, so it reads correctly in light and dark.
 */
export const surfaceVariants = {
  /** Faint inset panel — the default (e.g. an item panel inside a Card). */
  subtle: 'rounded-xl border border-border bg-muted/40',
  /** Stronger inset fill (e.g. a called-out inner block). */
  inset: 'rounded-xl border border-border bg-muted',
  /** A light raised inner surface on a tinted/muted background (e.g. a chip, a dropdown). */
  card: 'rounded-xl border border-border bg-card',
} as const;

export type SurfaceVariant = keyof typeof surfaceVariants;

export function surface(variant: SurfaceVariant = 'subtle'): string {
  return surfaceVariants[variant];
}
