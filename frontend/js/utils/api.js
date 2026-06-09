/* ═══════════════════════════════════════════════════════════════════════════
   api.js — Fetch wrapper with JWT injection + toast notifications
   ═══════════════════════════════════════════════════════════════════════════ */

const API_BASE = '/api';

export const Auth = {
  getToken()       { return localStorage.getItem('pfa_token'); },
  getRefresh()     { return localStorage.getItem('pfa_refresh'); },
  getUserId()      { return localStorage.getItem('pfa_userId'); },
  getEmail()       { return localStorage.getItem('pfa_email'); },
  isLoggedIn()     { return !!this.getToken(); },

  save(data) {
    localStorage.setItem('pfa_token',   data.token);
    localStorage.setItem('pfa_refresh', data.refreshToken);
    localStorage.setItem('pfa_userId',  data.userId);
    localStorage.setItem('pfa_email',   data.email);
  },

  clear() {
    ['pfa_token','pfa_refresh','pfa_userId','pfa_email'].forEach(k => localStorage.removeItem(k));
  }
};

async function request(method, path, { body, params, headers = {}, raw = false } = {}) {
  const url = new URL(API_BASE + path, location.origin);
  if (params) Object.entries(params).forEach(([k, v]) => { if (v != null) url.searchParams.set(k, v); });

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

  const res = await fetch(url.toString(), opts);

  if (res.status === 401 && path !== '/auth/login' && path !== '/auth/refresh') {
    Auth.clear();
    window.dispatchEvent(new Event('auth-expired'));
    throw new Error('Session expired');
  }

  if (raw) return res;

  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }

  if (!res.ok) {
    const msg = data?.message || data?.error || `Request failed (${res.status})`;
    throw new Error(msg);
  }
  return data;
}

export const api = {
  get:    (path, params)       => request('GET', path, { params }),
  post:   (path, body, opts)   => request('POST', path, { body, ...opts }),
  put:    (path, body)         => request('PUT', path, { body }),
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
