import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter } from 'next/font/google';
import { Toaster } from 'sonner';
import { Providers } from './providers';
import { ApiStatusIndicator } from '@/components/api-status-indicator';
import './globals.css';

const inter = Inter({ subsets: ['latin'], variable: '--font-sans', display: 'swap' });

export const metadata: Metadata = {
  title: 'App',
  description: 'Neutral deployable shell',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="min-h-dvh bg-white font-sans text-slate-900 antialiased">
        <Providers>
          <div className="flex min-h-dvh flex-col">
            <header className="flex items-center justify-between border-b border-slate-200 px-6 py-3">
              <span className="text-sm font-medium tracking-tight">App</span>
              <ApiStatusIndicator />
            </header>
            <main className="flex-1">{children}</main>
          </div>
          <Toaster position="top-right" />
        </Providers>
      </body>
    </html>
  );
}
