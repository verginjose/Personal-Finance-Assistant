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
  getDefaultCurrency()    { return localStorage.getItem('pfa_defaultCurrency') || 'INR'; },
  setDefaultCurrency(c)   { if (c) localStorage.setItem('pfa_defaultCurrency', String(c).toUpperCase()); },
  isLoggedIn()     { return !!this.getToken(); },

  save(data) {
    localStorage.setItem('pfa_token',   data.token);
    localStorage.setItem('pfa_refresh', data.refreshToken);
    localStorage.setItem('pfa_userId',  data.userId);
    localStorage.setItem('pfa_email',   data.email);
    localStorage.setItem('pfa_name',    data.name || data.username || '');
    if (data.defaultCurrency) this.setDefaultCurrency(data.defaultCurrency);
    scheduleProactiveRefresh();
  },

  clear() {
    ['pfa_token','pfa_refresh','pfa_userId','pfa_email','pfa_name','pfa_defaultCurrency']
      .forEach(k => localStorage.removeItem(k));
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
          const type = data.status === 'SUCCESS' ? 'success' : (data.status === 'ERROR' ? 'error' : 'info');
          // When the tab is in the background, an OS notification is the only
          // thing the user will actually see; in the foreground a toast is.
          showBrowserNotification(data);
          toast(data.message, type, { duration: type === 'error' ? 9000 : 7000 });

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
let proactiveRefreshTimer = null;

/** Access-token expiry from the JWT payload, or null when it cannot be read. */
function tokenExpiryMs(token) {
  try {
    const part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(part.padEnd(Math.ceil(part.length / 4) * 4, '=')));
    return payload.exp ? payload.exp * 1000 : null;
  } catch {
    return null;
  }
}

/** Ask the browser for notification permission (must be a user gesture). */
export async function requestBrowserNotificationPermission() {
  if (!('Notification' in window)) return 'unsupported';
  if (Notification.permission !== 'default') return Notification.permission;
  try { return await Notification.requestPermission(); } catch { return 'default'; }
}

function showBrowserNotification(data) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  // A toast already covers the foreground case; the OS notification is for
  // tabs the user cannot see.
  if (document.visibilityState === 'visible') return;
  try {
    const n = new Notification('Varavu', {
      body: data.message || 'New notification',
      tag: 'varavu-' + (data.event || data.type || 'notification'),
      icon: '/assets/varavu-mark.svg',
    });
    n.onclick = () => { window.focus(); n.close(); };
  } catch { /* some browsers reject notifications without a service worker */ }
}

async function refreshTokens() {
  if (!isRefreshing) {
    isRefreshing = true;
    const usedRefreshToken = Auth.getRefresh();
    refreshPromise = fetch(API_BASE + '/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: usedRefreshToken })
    }).then(async (refreshRes) => {
      if (!refreshRes.ok) {
        // Another tab may have rotated first and written a new pair; in that
        // case this 401 is not a dead session.
        if (Auth.getRefresh() && Auth.getRefresh() !== usedRefreshToken) {
          return Auth.getToken();
        }
        const err = new Error('Refresh failed');
        err.fatal = refreshRes.status === 401;
        throw err;
      }
      const data = await refreshRes.json();
      localStorage.setItem('pfa_token', data.token);
      localStorage.setItem('pfa_refresh', data.refreshToken);
      scheduleProactiveRefresh();
      // Re-establish SSE connection with the fresh token immediately
      SseManager.disconnect();
      SseManager.connect();
      return data.token;
    }).catch((err) => {
      // A network error or a 5xx must not end the session; the reactive path
      // can retry. Only an explicit 401 (and nobody else refreshing) does.
      if (err.fatal) {
        Auth.clear();
        window.dispatchEvent(new Event('auth-expired'));
      }
      throw err;
    }).finally(() => {
      isRefreshing = false;
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

/**
 * Refresh before the access token expires instead of waiting for a 401. Long-idle
 * tabs used to wake up holding a stale token; with rotation racing across tabs
 * that could end in a hard logout. Refreshing 5 minutes early avoids the race.
 */
export function scheduleProactiveRefresh() {
  clearTimeout(proactiveRefreshTimer);
  const token = Auth.getToken();
  if (!token) return;
  const exp = tokenExpiryMs(token);
  if (!exp) return;
  const delay = Math.max(30_000, exp - Date.now() - 5 * 60_000);
  proactiveRefreshTimer = setTimeout(() => {
    refreshTokens().catch(() => { /* reactive path will retry on 401 */ });
  }, Math.min(delay, 24 * 60 * 60_000));
}

// Another tab refreshing the session writes new tokens; follow along instead
// of replaying the consumed refresh token later.
window.addEventListener('storage', (e) => {
  if (e.key === 'pfa_token' || e.key === 'pfa_refresh') scheduleProactiveRefresh();
});

// A tab brought back to the foreground may have slept past its timer.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible' || !Auth.getToken()) return;
  const exp = tokenExpiryMs(Auth.getToken());
  if (exp && exp - Date.now() < 2 * 60_000) {
    refreshTokens().catch(() => {});
  } else {
    scheduleProactiveRefresh();
  }
});

scheduleProactiveRefresh();

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

  // These endpoints report "wrong password" as a 401 too. Never treat that as an
  // expired session: pass the response through so the form shows the real error.
  const isAuthRequest = path === '/auth/login' || path === '/auth/refresh' || path === '/auth/register';

  // Handle 401 Unauthorized for token refresh
  if (res.status === 401 && !isAuthRequest) {
    const refreshToken = Auth.getRefresh();
    if (refreshToken) {
      try {
        await refreshTokens(); // Wait for the refresh to finish
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

  if (res.status === 401 && !isAuthRequest) {
      Auth.clear();
      window.dispatchEvent(new Event('auth-expired'));
      throw new Error('Session expired');
  }

  if (raw) return res;

  // DELETE is idempotent: if the row is already gone (e.g. a delayed read
  // after a write showed it, and the user deleted it a second time), the
  // user's intent — the row no longer exists — is satisfied. Treat the 404
  // as success so the UI doesn't surface a spurious "not found" error.
  if (method === 'DELETE' && res.status === 404) {
    return null;
  }

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
/**
 * Bottom-centre toast. Pass `{ undo: fn }` for a reversible action; the undo
 * label reuses the action's verb by default ("Undo").
 */
export function toast(message, type = 'info', opts = {}) {
  const options = typeof opts === 'number' ? { duration: opts } : (opts || {});
  // Errors stay long enough to read; everything else gets a relaxed default.
  // Hovering pauses the countdown so it never disappears mid-read.
  const duration = options.duration ?? (type === 'error' ? 9000 : 6500);

  if (!toastContainer) {
    toastContainer = document.createElement('div');
    toastContainer.className = 'toast-container';
    toastContainer.setAttribute('role', 'status');
    toastContainer.setAttribute('aria-live', 'polite');
    document.body.appendChild(toastContainer);
  }
  const el = document.createElement('div');
  el.className = `toast toast-${type}`;
  const label = document.createElement('span');
  label.className = 'toast-message';
  label.textContent = message;
  el.appendChild(label);

  // A burst of identical messages (e.g. a repeatedly failing action) should
  // read as one toast, and the stack never grows past three. Premium apps feel
  // calm because the interface does not shout over itself.
  const toastKey = `${type}\u0000${message}`;
  el.dataset.toastKey = toastKey;
  const live = [...toastContainer.querySelectorAll('.toast:not(.toast--leaving)')];
  const duplicate = live.find((t) => t.dataset.toastKey === toastKey);
  if (duplicate && duplicate._restart) {
    duplicate._restart();
    return duplicate._dismiss;
  }
  while (live.length >= 3) {
    const oldest = live.shift();
    if (oldest._dismiss) oldest._dismiss();
  }

  let timer = null;
  let remaining = duration;
  let expiresAt = 0;

  const dismiss = () => {
    clearTimeout(timer);
    timer = null;
    el.classList.add('toast--leaving');
    setTimeout(() => el.remove(), 200);
  };

  const startTimer = () => {
    expiresAt = Date.now() + remaining;
    timer = setTimeout(dismiss, remaining);
  };
  const pauseTimer = () => {
    if (timer === null) return;
    clearTimeout(timer);
    timer = null;
    remaining = Math.max(250, expiresAt - Date.now());
  };
  const resumeTimer = () => {
    if (timer === null) startTimer();
  };

  // Identical toasts share a lifetime: a repeat resets the clock instead of
  // stacking. Exposed so the dedupe/cap above can reach this toast's controls.
  el._restart = () => {
    remaining = duration;
    clearTimeout(timer);
    timer = null;
    startTimer();
  };
  el._dismiss = dismiss;

  el.addEventListener('mouseenter', pauseTimer);
  el.addEventListener('mouseleave', resumeTimer);
  el.addEventListener('focusin', pauseTimer);
  el.addEventListener('focusout', resumeTimer);

  if (typeof options.undo === 'function') {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'toast-action';
    btn.textContent = options.undoLabel || 'Undo';
    btn.onclick = async () => {
      dismiss();
      try { await options.undo(); } catch (err) { toast(err.message || 'Could not undo', 'error'); }
    };
    el.appendChild(btn);
  }

  toastContainer.appendChild(el);
  startTimer();
  return dismiss;
}
