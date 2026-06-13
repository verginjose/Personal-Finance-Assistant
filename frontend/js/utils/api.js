/* ═══════════════════════════════════════════════════════════════════════════
   api.js — Fetch wrapper with JWT injection + toast notifications
   ═══════════════════════════════════════════════════════════════════════════ */

const API_BASE = '/api';

export const Auth = {
  getToken()       { return localStorage.getItem('pfa_token'); },
  getRefresh()     { return localStorage.getItem('pfa_refresh'); },
  getUserId()      { return localStorage.getItem('pfa_userId'); },
  getEmail()       { return localStorage.getItem('pfa_email'); },
  getName()               { return localStorage.getItem('pfa_name');},
  isLoggedIn()     { return !!this.getToken(); },

  save(data) {
    localStorage.setItem('pfa_token',   data.token);
    localStorage.setItem('pfa_refresh', data.refreshToken);
    localStorage.setItem('pfa_userId',  data.userId);
    localStorage.setItem('pfa_email',   data.email);
    localStorage.setItem('pfa_name', data.name);
  },

  clear() {
    ['pfa_token','pfa_refresh','pfa_userId','pfa_email'].forEach(k => localStorage.removeItem(k));
  }
};

export const SseManager = {
  eventSource: null,
  _reconnectTimer: null,
  _retryDelay: 3000,
  _maxRetryDelay: 30000,

  connect() {
    if (this.eventSource) return;          // already connected
    if (this._reconnectTimer) return;      // reconnect already scheduled

    const userId = Auth.getUserId();
    const token = Auth.getToken();
    if (!userId || !token) return;

    this.eventSource = new EventSource(`${API_BASE}/upsert/notifications/stream?token=${encodeURIComponent(token)}`);

    this.eventSource.onopen = () => {
      console.log('SSE connected');
      this._retryDelay = 3000;             // reset backoff on successful connect
    };

    // Listen to heartbeat pings so the browser doesn't treat them as unknown events
    this.eventSource.addEventListener('ping', () => { /* heartbeat — keep alive */ });

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
      // EventSource fires onerror for both transient network hiccups AND
      // permanent failures. If readyState is CONNECTING, the browser is
      // already retrying automatically — don't pile on a manual retry.
      if (this.eventSource && this.eventSource.readyState === EventSource.CONNECTING) {
        console.debug('SSE reconnecting (browser auto-retry)...');
        return;
      }

      console.warn('SSE connection closed. Will reconnect in', this._retryDelay / 1000, 's');
      this._closeSource();
      this._scheduleReconnect();
    };
  },

  _closeSource() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  },

  _scheduleReconnect() {
    if (this._reconnectTimer) return;      // already scheduled
    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null;
      this.connect();
    }, this._retryDelay);
    // Exponential backoff: 3s → 6s → 12s → 24s → 30s (capped)
    this._retryDelay = Math.min(this._retryDelay * 2, this._maxRetryDelay);
  },

  disconnect() {
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    this._retryDelay = 3000;
    this._closeSource();
  }
};

let isRefreshing = false;
let refreshPromise = null;

async function request(method, path, { body, params, headers = {}, raw = false } = {}) {
  const url = new URL(API_BASE + path, location.origin);
  if (params) Object.entries(params).forEach(([k, v]) => { if (v != null) url.searchParams.set(k, v); });

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
    return opts;
  };

  let res = await fetch(url.toString(), getOpts());

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
          
          // Re-establish SSE connection with the fresh token immediately
          // This prevents missed background events (like OCR completion)
          SseManager.disconnect();
          SseManager.connect();

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
          res = await fetch(url.toString(), getOpts()); // Retry the original request
        } catch (networkErr) {
          // Give the network 1 second to stabilize on wake-up and try one last time
          await new Promise(r => setTimeout(r, 1000));
          res = await fetch(url.toString(), getOpts());
        }
      } catch (e) {
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
  return data;
}

export const api = {
  get:    (path, params)       => request('GET', path, { params }),
  post:   (path, body, opts)   => request('POST', path, { body, ...opts }),
  put:    (path, body, opts)   => request('PUT', path, { body, ...opts }),
  patch:  (path, body, params) => request('PATCH', path, { body, params }),
  delete: (path, params)       => request('DELETE', path, { params }),
  upload: (path, formData)     => request('POST', path, { body: formData }),
  raw:    (method, path, opts) => request(method, path, { ...opts, raw: true }),
};

/* ── Toast helper ──────────────────────────────────────────────────────── */
let toastContainer;
export function toast(message, type = 'info', duration = 3500) {
  if (!toastContainer) {
    toastContainer = document.createElement('div');
    toastContainer.className = 'toast-container';
    document.body.appendChild(toastContainer);
  }
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  el.textContent = message;
  toastContainer.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; setTimeout(() => el.remove(), 300); }, duration);
}
