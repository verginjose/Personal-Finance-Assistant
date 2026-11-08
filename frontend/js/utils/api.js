/* ═══════════════════════════════════════════════════════════════════════════
   api.js — Fetch wrapper with JWT injection + toast notifications
   ═══════════════════════════════════════════════════════════════════════════ */

const API_BASE = '/api';

/**
 * Session storage policy.
 *
 * The access token lives only in this module (memory). The refresh token is an
 * HttpOnly, SameSite cookie set by auth-service and is never readable from
 * JavaScript. A localStorage flag records that a session may exist so the
 * shell can render and silently refresh after a page reload; it carries no
 * credential, so it is safe for scripts to see.
 */
const SESSION_FLAG = 'pfa_session';
let accessToken = null;

export const Auth = {
  getToken()       { return accessToken; },
  // Kept for callers that predate the cookie migration. There is no
  // JS-readable refresh token any more.
  getRefresh()     { return null; },
  getUserId()      { return localStorage.getItem('pfa_userId'); },
  getEmail()       { return localStorage.getItem('pfa_email'); },
  getName()               { return localStorage.getItem('pfa_name');},
  getDefaultCurrency()    { return localStorage.getItem('pfa_defaultCurrency') || 'INR'; },
  setDefaultCurrency(c)   { if (c) localStorage.setItem('pfa_defaultCurrency', String(c).toUpperCase()); },
  hasSession()     { try { return localStorage.getItem(SESSION_FLAG) === '1'; } catch { return false; } },
  isLoggedIn()     { return !!accessToken || this.hasSession(); },

  save(data) {
    accessToken = data.token || null;
    localStorage.setItem('pfa_userId',  data.userId);
    localStorage.setItem('pfa_email',   data.email);
    localStorage.setItem('pfa_name',    data.name || data.username || '');
    if (data.defaultCurrency) this.setDefaultCurrency(data.defaultCurrency);
    try { localStorage.setItem(SESSION_FLAG, '1'); } catch { /* storage unavailable */ }
    scheduleProactiveRefresh();
  },

  clear() {
    accessToken = null;
    ['pfa_token','pfa_refresh','pfa_userId','pfa_email','pfa_name','pfa_defaultCurrency', SESSION_FLAG]
      .forEach(k => localStorage.removeItem(k));
  }
};

export const SseManager = {
  eventSource: null,
  _reconnectTimer: null,
  _connecting: false,
  _retryDelay: 3000,
  _maxRetryDelay: 30000,

  connect() {
    if (this.eventSource) return;          // already connected
    if (this._reconnectTimer) return;      // reconnect already scheduled
    if (this._connecting) return;          // ticket request in flight

    const userId = Auth.getUserId();
    if (!userId) return;

    // EventSource cannot set an Authorization header. Rather than put the
    // long-lived access token in the URL (history, Referer, nginx logs), ask
    // auth-service for a short-lived single-purpose stream ticket.
    this._connecting = true;
    request('POST', '/auth/stream-ticket')
      .then((data) => {
        if (!data || !data.ticket) throw new Error('No stream ticket');
        this.eventSource = new EventSource(
          `${API_BASE}/upsert/notifications/stream?ticket=${encodeURIComponent(data.ticket)}`);
        this._wireSource();
      })
      .catch(() => { this._scheduleReconnect(); })
      .finally(() => { this._connecting = false; });
  },

  _wireSource() {
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
      icon: '/assets/varavu-mark.png',
    });
    n.onclick = () => { window.focus(); n.close(); };
  } catch { /* some browsers reject notifications without a service worker */ }
}

/**
 * Rotate the session using the HttpOnly refresh cookie. The cookie is sent
 * automatically (same-origin), and the new access token is kept in memory.
 */
async function refreshTokens() {
  if (!isRefreshing) {
    isRefreshing = true;
    refreshPromise = fetch(API_BASE + '/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    }).then(async (refreshRes) => {
      if (!refreshRes.ok) {
        const err = new Error('Refresh failed');
        err.fatal = refreshRes.status === 401 || refreshRes.status === 403;
        throw err;
      }
      const data = await refreshRes.json();
      if (!data || !data.token) throw new Error('Refresh returned no token');
      // Only the access token changes. Keep the stored identity fields: a
      // refresh response does not repeat the display name.
      accessToken = data.token;
      try { localStorage.setItem(SESSION_FLAG, '1'); } catch { /* storage unavailable */ }
      scheduleProactiveRefresh();
      // Re-establish SSE connection with a fresh ticket immediately.
      SseManager.disconnect();
      SseManager.connect();
      return data.token;
    }).catch((err) => {
      // A network error or a 5xx must not end the session; the reactive path
      // can retry. Only an explicit 401/403 does.
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
 * Restore the in-memory access token after a page reload. The refresh cookie
 * is the only credential that survives a reload, so one silent refresh is
 * enough to re-establish the session.
 */
export async function ensureSession({ force = false } = {}) {
  if (Auth.getToken() || (!force && !Auth.hasSession())) return;
  try { await refreshTokens(); } catch { /* auth-expired handles the UI */ }
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

// Another tab signing in or out changes the shared session flag. Follow it:
// reuse the cookie session, or drop the in-memory token.
window.addEventListener('storage', (e) => {
  if (e.key !== SESSION_FLAG) return;
  if (e.newValue === '1') {
    ensureSession();
  } else if (e.newValue === null) {
    // Another tab cleared the flag (a failed refresh or a logout), but the
    // HttpOnly cookie is the real credential and may still be valid. Try it
    // before dropping this tab's session; refreshTokens() ends the session
    // itself only if the cookie is gone too.
    accessToken = null;
    ensureSession({ force: true });
  }
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
    const opts = { method, headers: { ...headers }, credentials: 'same-origin' };
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

  // Handle 401 Unauthorized for token refresh. The refresh credential is an
  // HttpOnly cookie, so attempt one silent refresh even when the localStorage
  // flag is missing: a tab that never saw the login, or one whose flag another
  // tab cleared, can still hold a perfectly good cookie. Only an explicit
  // 401/403 from /auth/refresh ends the session.
  if (res.status === 401 && !isAuthRequest) {
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
      // A network error or a 5xx from /auth/refresh is not a logout; the
      // cookie may still be valid once the service is reachable again.
      if (e.fatal) throw new Error('Session expired. Please log in again.');
      throw new Error('Could not reach the server. Please try again.');
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
