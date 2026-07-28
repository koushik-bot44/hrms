/*
 * HRORGS Web Push service worker (Stage N2).
 * Push-ONLY: it shows OS notifications from 'push' events and routes 'notificationclick' — it does NOT
 * intercept fetch or cache anything, so HRORGS is deliberately NOT turned into an offline/PWA app.
 *  - 'install'/'activate' -> take control immediately so an updated SW applies predictably.
 *  - 'push' -> render the JSON payload ({title, body, url}) as a notification (sane defaults if missing).
 *  - 'notificationclick' -> focus an open HRORGS window, else open one at the payload url.
 * N3 will trigger the pushes (on new mail); this SW just renders whatever the server sends.
 */

self.addEventListener('install', () => {
  // Activate this version without waiting for old tabs to close.
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  // Start controlling already-open pages so behavior is predictable right after an update.
  event.waitUntil(self.clients.claim());
});

self.addEventListener('push', (event) => {
  let data = { title: 'HRORGS', body: 'You have a new notification.', url: '/' };
  try {
    if (event.data) {
      data = { ...data, ...event.data.json() };
    }
  } catch (_) {
    // Non-JSON payloads still render with the defaults above.
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'HRORGS', {
      body: data.body || '',
      data: { url: data.url || '/' },
      // Coalesce bursts under one tag but still alert on each (renotify) so nothing is silently dropped.
      tag: 'ihrms',
      renotify: true,
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        // Reuse an already-open HRORGS window: focus it (and navigate where supported).
        if ('focus' in client) {
          const focused = client.focus();
          if ('navigate' in client) {
            return Promise.resolve(focused).then(() => client.navigate(target).catch(() => {}));
          }
          return focused;
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(target);
      return undefined;
    }),
  );
});
