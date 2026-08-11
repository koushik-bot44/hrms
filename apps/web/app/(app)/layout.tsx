import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter } from 'next/font/google';
import { Providers } from '../providers';
import { Toaster } from '@/components/ui/sonner';
import { SIGNATURE_FONT_VARS } from '../fonts';
import '../globals.css';

const inter = Inter({ subsets: ['latin'], variable: '--font-sans', display: 'swap' });

export const metadata: Metadata = {
  title: { default: 'hrorg.in', template: '%s · hrorg.in' },
  description: 'Employee information & onboarding management system.',
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
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`${inter.variable} ${SIGNATURE_FONT_VARS}`} suppressHydrationWarning>
      <body className="min-h-dvh bg-background font-sans text-foreground antialiased">
        <Providers>
          {children}
          <Toaster />
        </Providers>
      </body>
    </html>
  );
}
