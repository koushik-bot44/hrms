/*
 * Minimal Web Push service worker (Stage N1).
 * A PushSubscription REQUIRES a service worker, so this is the smallest one that proves the pipe:
 *  - 'push'  -> show the notification carried in the (JSON) payload
 *  - 'notificationclick' -> focus an existing tab (or open one) at the payload url
 * Stage N2 replaces this with the real opt-in UX + richer handling (actions, icons, grouping).
 */

self.addEventListener('push', (event) => {
  let data = { title: 'IHRMS', body: 'You have a new notification.', url: '/' };
  try {
    if (event.data) {
      data = { ...data, ...event.data.json() };
    }
  } catch (_) {
    // Non-JSON payloads still render with the defaults above.
  }
  event.waitUntil(
    self.registration.showNotification(data.title || 'IHRMS', {
      body: data.body || '',
      data: { url: data.url || '/' },
      tag: 'ihrms',
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ('focus' in client) {
          client.focus();
          if ('navigate' in client) client.navigate(url);
          return;
        }
      }
      if (self.clients.openWindow) return self.clients.openWindow(url);
    }),
  );
});
