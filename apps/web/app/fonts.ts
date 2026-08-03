import { Dancing_Script, Great_Vibes, Caveat } from 'next/font/google';

/**
 * Three script faces for the SignatureCapture "Generate" mode. All are SIL Open Font License 1.1 and are
 * SELF-HOSTED by next/font at build time (no runtime external fetch — the app's normal font pipeline). Each
 * is exposed as a CSS variable so the signature canvas can resolve the actual (build-hashed) family name via
 * getComputedStyle before rasterizing with fillText.
 */
export const sigFlowing = Dancing_Script({
  subsets: ['latin'],
  weight: '600',
  display: 'swap',
  variable: '--font-sig-flowing',
});
export const sigElegant = Great_Vibes({
  subsets: ['latin'],
  weight: '400',
  display: 'swap',
  variable: '--font-sig-elegant',
});
export const sigCasual = Caveat({
  subsets: ['latin'],
  weight: '600',
  display: 'swap',
  variable: '--font-sig-casual',
});

/** Space-joined `.variable` classes to hang on <html> so the CSS vars resolve app-wide. */
export const SIGNATURE_FONT_VARS = `${sigFlowing.variable} ${sigElegant.variable} ${sigCasual.variable}`;
