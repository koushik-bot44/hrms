import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter } from 'next/font/google';
import { MarketingNav } from '@/components/marketing/marketing-nav';
import { SiteFooter } from '@/components/marketing/site-footer';
import { BRAND_NAME, BRAND_TITLE } from '@/lib/brand';
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

const OG_IMAGE = {
  url: '/brand/og-image.png',
  width: 1200,
  height: 630,
  alt: BRAND_TITLE,
};

export const metadata: Metadata = {
  ...(SITE_URL ? { metadataBase: new URL(SITE_URL) } : {}),
  title: BRAND_TITLE,
  description: DESCRIPTION,
  applicationName: BRAND_NAME,
  robots: { index: true, follow: true },
  // Browser-tab + app icons — the hrorg.in mark, served from the SITE ROOT (`/favicon.ico`, `/icon.png`) so the
  // default `/favicon.ico` request resolves (Google's favicon crawler + browsers rely on the root URL).
  icons: {
    icon: [
      { url: '/favicon.ico', sizes: 'any' },
      { url: '/icon.png', type: 'image/png', sizes: '512x512' },
    ],
    apple: [{ url: '/apple-icon.png', sizes: '180x180' }],
    shortcut: ['/favicon.ico'],
  },
  openGraph: {
    type: 'website',
    title: BRAND_TITLE,
    description: DESCRIPTION,
    siteName: BRAND_NAME,
    images: [OG_IMAGE],
  },
  twitter: {
    card: 'summary_large_image',
    title: BRAND_TITLE,
    description: DESCRIPTION,
    images: [OG_IMAGE.url],
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
