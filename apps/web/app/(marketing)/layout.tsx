import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter } from 'next/font/google';
import { MarketingNav } from '@/components/marketing/marketing-nav';
import { SiteFooter } from '@/components/marketing/site-footer';
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

const DESCRIPTION =
  'hrorg.in runs and secures internal HR operations for organizations — onboarding to exit, with e-signed ' +
  'employment documents, attendance and leave, internal communications, and audit-ready records. Confidential ' +
  'by design.';

export const metadata: Metadata = {
  ...(SITE_URL ? { metadataBase: new URL(SITE_URL) } : {}),
  title: 'hrorg.in — Internal HR management services',
  description: DESCRIPTION,
  applicationName: 'hrorg.in',
  robots: { index: true, follow: true },
  openGraph: {
    type: 'website',
    title: 'hrorg.in — Internal HR management services',
    description: DESCRIPTION,
    siteName: 'hrorg.in',
  },
  twitter: {
    card: 'summary',
    title: 'hrorg.in — Internal HR management services',
    description: DESCRIPTION,
  },
};

export default function MarketingRootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`marketing scroll-smooth ${inter.variable}`} suppressHydrationWarning>
      <body className="min-h-dvh bg-background font-sans text-foreground antialiased">
        <div className="relative flex min-h-dvh flex-col">
          <MarketingNav />
          <main className="flex-1">{children}</main>
          <SiteFooter />
        </div>
      </body>
    </html>
  );
}
