// Offline support. The app shell is cached on install so Spendly opens with no
// connection; pdf.js is cached only once it has actually been fetched.

const VERSION = 'spendly-v1';
const SHELL = [
  './',
  'index.html',
  'manifest.webmanifest',
  'css/styles.css',
  'js/app.js',
  'js/db.js',
  'js/ui.js',
  'js/money.js',
  'js/transfer.js',
  'js/statement.js',
  'js/pdf.js',
  'js/views/add.js',
  'js/views/calendar.js',
  'js/views/edit.js',
  'js/views/data.js',
  'js/views/settings.js',
  'icons/icon.svg',
  'icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION)
      // addAll fails the whole install if any one file 404s, so each is added
      // individually: a missing icon must not leave the app with no cache.
      .then((cache) => Promise.all(SHELL.map((url) => cache.add(url).catch(() => {}))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  // Network first for our own files, so a deployed update is picked up
  // promptly; cache is the fallback when offline.
  if (new URL(request.url).origin === self.location.origin) {
    event.respondWith(
      fetch(request)
        .then((res) => {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(request, copy)).catch(() => {});
          return res;
        })
        .catch(() => caches.match(request).then((hit) => hit || caches.match('index.html'))),
    );
    return;
  }

  // Cache first for pdf.js: it is versioned in its URL and large.
  event.respondWith(
    caches.match(request).then((hit) => hit || fetch(request).then((res) => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(VERSION).then((c) => c.put(request, copy)).catch(() => {});
      }
      return res;
    })),
  );
});
