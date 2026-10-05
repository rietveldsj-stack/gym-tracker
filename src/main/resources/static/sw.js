// Serves the app's own files from the cache straight away and refreshes them in the background,
// so the app opens instantly (also offline) and picks up a new deploy on the next launch.
const CACHE = 'gym-tracker-v1';
const ASSETS = [
  '/',
  '/index.html',
  '/styles.css',
  '/manifest.json',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/apple-touch-icon.png',
  '/js/app.js',
  '/js/api.js',
  '/js/format.js',
  '/js/prefs.js',
  '/js/push.js',
  '/js/rest.js',
  '/js/records.js',
  '/js/router.js',
  '/js/store.js',
  '/js/sync.js',
  '/js/ui.js',
  '/js/util.js',
  '/js/views/exercises.js',
  '/js/views/history.js',
  '/js/views/login.js',
  '/js/views/settings.js',
  '/js/views/workout.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/')
      || url.pathname === '/login' || url.pathname.startsWith('/test/')) return;

  const key = event.request.mode === 'navigate' ? '/index.html' : url.pathname;
  event.respondWith(caches.open(CACHE).then(async (cache) => {
    const cached = await cache.match(key);
    const network = fetch(event.request).then((response) => {
      if (response.ok) cache.put(key, response.clone());
      return response;
    });
    if (cached) {
      event.waitUntil(network.catch(() => {}));
      return cached;
    }
    return network;
  }));
});

self.addEventListener('push', (event) => {
  const data = event.data ? event.data.json() : { title: "Rest's over", body: 'Time for your next set 💪' };
  event.waitUntil(self.registration.showNotification(data.title, {
    body: data.body,
    icon: '/icons/icon-192.png',
    tag: 'rest-timer',
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
    if (windows.length) return windows[0].focus();
    return self.clients.openWindow('/');
  }));
});
