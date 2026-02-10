// Service Worker for Romance Progression PWA
const CACHE_NAME = 'rp-v3';
const ASSETS = [
  './',
  './index.html',
  './js/main.js',
  'https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css'
];

// Files that should use stale-while-revalidate (always fetch fresh in background)
const REVALIDATE_PATTERNS = ['/js/', '.html', '/'];

function shouldRevalidate(url) {
  return REVALIDATE_PATTERNS.some(p => url.includes(p) || url.endsWith(p));
}

// Install - cache assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS);
    })
  );
  self.skipWaiting();
});

// Activate - clean old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((key) => key !== CACHE_NAME)
            .map((key) => caches.delete(key))
      );
    })
  );
  self.clients.claim();
});

// Fetch - stale-while-revalidate for app files, cache-first for static assets
self.addEventListener('fetch', (event) => {
  const url = event.request.url;
  
  if (shouldRevalidate(url)) {
    // Stale-while-revalidate: serve cached immediately, fetch fresh in background
    event.respondWith(
      caches.open(CACHE_NAME).then((cache) => {
        return cache.match(event.request).then((cached) => {
          const fetchPromise = fetch(event.request).then((response) => {
            if (response.status === 200) {
              cache.put(event.request, response.clone());
            }
            return response;
          }).catch(() => cached);
          
          return cached || fetchPromise;
        });
      })
    );
  } else {
    // Cache-first for static assets (CSS, fonts, images)
    event.respondWith(
      caches.match(event.request).then((cached) => {
        return cached || fetch(event.request).then((response) => {
          if (response.status === 200) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => {
              cache.put(event.request, clone);
            });
          }
          return response;
        });
      }).catch(() => {
        if (event.request.mode === 'navigate') {
          return caches.match('./');
        }
      })
    );
  }
});
