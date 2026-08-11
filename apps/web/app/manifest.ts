import type { MetadataRoute } from 'next';

/**
 * Web App Manifest — makes hrorg.in installable ("Add to Home Screen"). Next serves this at
 * `/manifest.webmanifest` and injects the `<link rel="manifest">`. `start_url` is the sign-in door
 * (`/login`), which forwards a live session to the user's home or shows the form. The icons are the root
 * hrorg.in mark (192 + 512 "any", plus a padded 512 "maskable" for Android adaptive shapes).
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    id: '/',
    name: 'hrorg.in — Integrated Human Resource Management Services',
    short_name: 'hrorg.in',
    description:
      'Sign in to hrorg.in — onboarding, e-signed documents, attendance & leave, internal mail and more.',
    start_url: '/login',
    scope: '/',
    display: 'standalone',
    orientation: 'portrait-primary',
    background_color: '#0a1224',
    theme_color: '#0a1224',
    icons: [
      { src: '/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icon.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icon-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  };
}
