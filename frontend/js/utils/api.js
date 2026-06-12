/* ═══════════════════════════════════════════════════════════════════════════
   api.js — Fetch wrapper with JWT injection + toast notifications
   ═══════════════════════════════════════════════════════════════════════════ */

const API_BASE = '/api';

export const Auth = {
  getToken()       { return localStorage.getItem('pfa_token'); },
  getRefresh()     { return localStorage.getItem('pfa_refresh'); },
  getUserId()      { return localStorage.getItem('pfa_userId'); },
  getEmail()       { return localStorage.getItem('pfa_email'); },
  getName()        { return localStorage.getItem('pfa_name'); },
  isLoggedIn()     { return !!this.getToken(); },

  save(data) {
    localStorage.setItem('pfa_token',   data.token);
    localStorage.setItem('pfa_refresh', data.refreshToken);
    localStorage.setItem('pfa_userId',  data.userId);
    localStorage.setItem('pfa_email',   data.email);
    localStorage.setItem('pfa_name', data.name);
  },

  clear() {
    ['pfa_token','pfa_refresh','pfa_userId','pfa_email','pfa_name'].forEach(k => localStorage.removeItem(k));
  }
};

export const SseManager = {
  eventSource: null,
  connect() {
    if (this.eventSource) return;
    const userId = Auth.getUserId();
    const token = Auth.getToken();
    if (!userId || !token) return;

    this.eventSource = new EventSource(`${API_BASE}/upsert/notifications/stream?token=${encodeURIComponent(token)}`);
    
    this.eventSource.onopen = () => console.log('SSE connected');
    
    this.eventSource.addEventListener('notification', (e) => {
      try {
        const data = JSON.parse(e.data);
        if (data.status === 'SUCCESS' || data.status === 'ERROR' || data.status === 'INFO') {
          // INFO type is new, typically for alerts. Toast the message.
          toast(data.message, data.status === 'SUCCESS' ? 'success' : (data.status === 'ERROR' ? 'error' : 'info'));
          
          // Dispatch specific event if provided, otherwise a generic one
          if (data.event) {
            window.dispatchEvent(new CustomEvent(data.event, { detail: data }));
          } else {
             // Fallback for old OCR events
             window.dispatchEvent(new CustomEvent('ocr-completed', { detail: data }));
          }
          // Also dispatch a generic notification event
          window.dispatchEvent(new CustomEvent('app-notification', { detail: data }));
        }
      } catch (err) {
        console.error('Failed to parse SSE notification', err);
      }
    });

    this.eventSource.onerror = (err) => {
      console.warn('SSE connection dropped. Reconnecting...', err);
      this.disconnect();
      // Reconnect after 3s
      setTimeout(() => this.connect(), 3000);
    };
  },
  disconnect() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }
};

/* ── AbortController for view-scoped request cancellation ────────────────── */
let activeAbortController = null;

/**
 * Call before rendering a new view to cancel in-flight requests from the
 * previous view (prevents stale data from rendering into a new page).
 */
export function abortPendingRequests() {
  if (activeAbortController) {
    activeAbortController.abort();
  }
  activeAbortController = new AbortController();
  return activeAbortController.signal;
}

export function getAbortSignal() {
  if (!activeAbortController) activeAbortController = new AbortController();
  return activeAbortController.signal;
}

/* ── Lightweight GET response cache ──────────────────────────────────────── */
const responseCache = new Map();
const CACHE_TTL_MS = 60_000; // 60 seconds

function getCacheKey(method, url) {
  return `${method}:${url}`;
}

function getCachedResponse(key) {
  const entry = responseCache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.ts > CACHE_TTL_MS) {
    responseCache.delete(key);
    return null;
  }
  return entry.data;
}

function setCachedResponse(key, data) {
  responseCache.set(key, { data, ts: Date.now() });
}

/** Invalidate all cached GET responses (e.g. after a mutation). */
export function invalidateCache() {
  responseCache.clear();
}

/* ── Core request function ───────────────────────────────────────────────── */
let isRefreshing = false;
let refreshPromise = null;

async function request(method, path, { body, params, headers = {}, raw = false, cache = false } = {}) {
  const url = new URL(API_BASE + path, location.origin);
  if (params) Object.entries(params).forEach(([k, v]) => { if (v != null) url.searchParams.set(k, v); });

  // Check cache for GET requests
  const urlStr = url.toString();
  if (cache && method === 'GET') {
    const cached = getCachedResponse(getCacheKey(method, urlStr));
    if (cached) return cached;
  }

  // Invalidate cache on mutations
  if (method !== 'GET') {
    invalidateCache();
  }

  const getOpts = () => {
    const opts = { method, headers: { ...headers } };
    const token = Auth.getToken();
    if (token) opts.headers['Authorization'] = `Bearer ${token}`;

    if (body instanceof FormData) {
      opts.body = body;
    } else if (body) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }

    const userId = Auth.getUserId();
    if (userId) opts.headers['X-User-Id'] = userId;

    // Attach abort signal (ignore for auth endpoints to avoid cancelling login mid-flight)
    if (activeAbortController && path !== '/auth/login' && path !== '/auth/register') {
      opts.signal = activeAbortController.signal;
    }

    return opts;
  };

  let res;
  try {
    res = await fetch(urlStr, getOpts());
  } catch (err) {
    if (err.name === 'AbortError') throw err; // Let callers handle abort gracefully
    throw err;
  }

  // Handle 401 Unauthorized for token refresh
  if (res.status === 401 && path !== '/auth/login' && path !== '/auth/refresh' && path !== '/auth/register') {
    const refreshToken = Auth.getRefresh();
    if (refreshToken) {
      if (!isRefreshing) {
        isRefreshing = true;
        refreshPromise = fetch(API_BASE + '/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken })
        }).then(async (refreshRes) => {
          if (!refreshRes.ok) throw new Error('Refresh failed');
          const data = await refreshRes.json();
          // Update tokens without overwriting existing email/name with undefined
          localStorage.setItem('pfa_token', data.token);
          localStorage.setItem('pfa_refresh', data.refreshToken);
          return data.token;
        }).catch((err) => {
          Auth.clear();
          window.dispatchEvent(new Event('auth-expired'));
          throw err;
        }).finally(() => {
          isRefreshing = false;
          refreshPromise = null;
        });
      }

      try {
        await refreshPromise; // Wait for the refresh to finish
        try {
          res = await fetch(urlStr, getOpts()); // Retry the original request
        } catch (networkErr) {
          if (networkErr.name === 'AbortError') throw networkErr;
          // Give the network 1 second to stabilize on wake-up and try one last time
          await new Promise(r => setTimeout(r, 1000));
          res = await fetch(urlStr, getOpts());
        }
      } catch (e) {
        if (e.name === 'AbortError') throw e;
        throw new Error('Session expired. Please log in again.');
      }
    } else {
      Auth.clear();
      window.dispatchEvent(new Event('auth-expired'));
      throw new Error('Session expired');
    }
  }

  if (res.status === 401) {
      Auth.clear();
      window.dispatchEvent(new Event('auth-expired'));
      throw new Error('Session expired');
  }

  if (raw) return res;

  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }

  if (!res.ok) {
    let msg = data?.message || data?.error || `Request failed (${res.status})`;
    if (Array.isArray(data?.details) && data.details.length) {
      msg += ': ' + data.details.join('; ');
    }
    throw new Error(msg);
  }

  // Cache successful GET responses if caching was requested
  if (cache && method === 'GET') {
    setCachedResponse(getCacheKey(method, urlStr), data);
  }

  return data;
}

export const api = {
  get:    (path, params, opts)   => request('GET', path, { params, ...opts }),
  post:   (path, body, opts)     => request('POST', path, { body, ...opts }),
  put:    (path, body, opts)     => request('PUT', path, { body, ...opts }),
  patch:  (path, body, params)   => request('PATCH', path, { body, params }),
  delete: (path, params)         => request('DELETE', path, { params }),
  upload: (path, formData)       => request('POST', path, { body: formData }),
  raw:    (method, path, opts)   => request(method, path, { ...opts, raw: true }),
  /** GET with in-memory caching (60s TTL). Use for dashboard/analytics data. */
  cachedGet: (path, params)      => request('GET', path, { params, cache: true }),
};

/* ── Toast helper ──────────────────────────────────────────────────────── */
const TOAST_ICONS = {
  success: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M9 12l2 2 4-4"/></svg>',
  error:   '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M15 9l-6 6"/><path d="M9 9l6 6"/></svg>',
  info:    '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>'
};

let toastContainer;
export function toast(message, type = 'info', duration = 3500) {
  if (!toastContainer) {
    toastContainer = document.createElement('div');
    toastContainer.className = 'toast-container';
    toastContainer.setAttribute('role', 'status');
    toastContainer.setAttribute('aria-live', 'polite');
    toastContainer.setAttribute('aria-atomic', 'false');
    document.body.appendChild(toastContainer);
  }
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  el.setAttribute('role', 'alert');
  el.innerHTML = `${TOAST_ICONS[type] || TOAST_ICONS.info}<span>${escapeHtml(message)}</span>`;
  toastContainer.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; setTimeout(() => el.remove(), 300); }, duration);
}

function escapeHtml(s) {
  const d = document.createElement('div');
  d.textContent = s ?? '';
  return d.innerHTML;
}
