import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter } from 'next/font/google';
import { Toaster } from 'sonner';
import { Providers } from './providers';
import { TopBar } from '@/components/top-bar';
import { SiteFooter } from '@/components/site-footer';
import { cn } from '@/lib/utils';
import './globals.css';

const inter = Inter({
  subsets: ['latin'],
  variable: '--font-sans',
  display: 'swap',
});

export const metadata: Metadata = {
  title: {
    default: 'CDPP — Document Provisioning Platform',
    template: '%s · CDPP',
  },
  description:
    'Issue, collect, and reference compliance documents — each verifiable by a unique ID.',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={cn(inter.variable)} suppressHydrationWarning>
      <body className="min-h-dvh bg-background font-sans antialiased">
        <Providers>
          <div className="flex min-h-dvh flex-col">
            <TopBar />
            <main className="flex-1">{children}</main>
            <SiteFooter />
          </div>
          <Toaster position="top-right" richColors closeButton />
        </Providers>
      </body>
    </html>
  );
}
