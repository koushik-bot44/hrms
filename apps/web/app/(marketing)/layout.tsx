import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter } from 'next/font/google';
import '../globals.css';

const inter = Inter({ subsets: ['latin'], variable: '--font-sans', display: 'swap' });

/**
 * ROOT layout for the public marketing one-pager at `/`. This is a SECOND root layout (Next.js multiple-root
 * pattern via route groups): it owns its own <html>/<body> and, crucially, mounts **no providers** — no
 * QueryClient, no AuthProvider — so the marketing page makes ZERO backend calls and renders identically for a
 * signed-in or signed-out visitor. The app's providers live in the sibling `(app)` root layout. The surface is
 * dark-first and independent of the app's light/dark theme via the `.marketing` token scope (globals.css).
 */

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL;

export const metadata: Metadata = {
  ...(SITE_URL ? { metadataBase: new URL(SITE_URL) } : {}),
  title: 'IHRMS — The complete employee lifecycle platform',
  description:
    'IHRMS runs the entire employee lifecycle for multi-company organizations — offer letters, e-signatures, ' +
    'attendance, leave, internal mail, and audited legal documents in one governed system.',
  applicationName: 'IHRMS',
  robots: { index: true, follow: true },
  openGraph: {
    type: 'website',
    title: 'IHRMS — The complete employee lifecycle platform',
    description:
      'Onboarding to offboarding — offer letters, e-signatures, attendance, leave, internal mail, and audited ' +
      'legal documents in one system.',
    siteName: 'IHRMS',
  },
  twitter: {
    card: 'summary',
    title: 'IHRMS — The complete employee lifecycle platform',
    description:
      'Onboarding to offboarding — offer letters, e-signatures, attendance, leave, internal mail, and audited ' +
      'legal documents in one system.',
  },
};

export default function MarketingRootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`marketing scroll-smooth ${inter.variable}`} suppressHydrationWarning>
      <body className="min-h-dvh bg-background font-sans text-foreground antialiased">{children}</body>
    </html>
  );
}
