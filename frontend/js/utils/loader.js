/* ═══════════════════════════════════════════════════════════════════════════
   loader.js — Lazy script/stylesheet loader with caching
   ═══════════════════════════════════════════════════════════════════════════ */

const loaded = new Map();

/**
 * Dynamically load a JS script. Returns a promise that resolves when loaded.
 * Subsequent calls with the same URL resolve immediately from cache.
 */
export function loadScript(url) {
  if (loaded.has(url)) return loaded.get(url);
  const p = new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = url;
    s.async = true;
    s.onload = resolve;
    s.onerror = () => reject(new Error(`Failed to load script: ${url}`));
    document.head.appendChild(s);
  });
  loaded.set(url, p);
  return p;
}

/**
 * Dynamically load a CSS stylesheet. Returns a promise that resolves when loaded.
 */
export function loadStylesheet(url) {
  if (loaded.has(url)) return loaded.get(url);
  const p = new Promise((resolve, reject) => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = url;
    link.onload = resolve;
    link.onerror = () => reject(new Error(`Failed to load stylesheet: ${url}`));
    document.head.appendChild(link);
  });
  loaded.set(url, p);
  return p;
}

/* ── Library bundles — load groups of related assets ──────────────────────── */

export async function loadChartJs() {
  await loadScript('https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js');
}

export async function loadCropperJs() {
  await Promise.all([
    loadStylesheet('https://cdnjs.cloudflare.com/ajax/libs/cropperjs/1.6.1/cropper.min.css'),
    loadScript('https://cdnjs.cloudflare.com/ajax/libs/cropperjs/1.6.1/cropper.min.js')
  ]);
}

export async function loadFlatpickr() {
  await Promise.all([
    loadStylesheet('https://cdn.jsdelivr.net/npm/flatpickr/dist/flatpickr.min.css'),
    loadStylesheet('https://cdn.jsdelivr.net/npm/flatpickr/dist/themes/dark.css'),
    loadScript('https://cdn.jsdelivr.net/npm/flatpickr')
  ]);
}

export async function loadTomSelect() {
  await Promise.all([
    loadStylesheet('https://cdn.jsdelivr.net/npm/tom-select@2.3.1/dist/css/tom-select.css'),
    loadScript('https://cdn.jsdelivr.net/npm/tom-select@2.3.1/dist/js/tom-select.complete.min.js')
  ]);
}
