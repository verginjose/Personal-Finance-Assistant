/* ═══════════════════════════════════════════════════════════════════════════
   ui.js — Shared formatting, theme, and component builders
   ═══════════════════════════════════════════════════════════════════════════ */
import { icon, categoryIcon } from './icons.js';

export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s ?? '';
  return d.innerHTML;
}

/* ── Names ──────────────────────────────────────────────────────────────── */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME_ACRONYMS = new Set(['OTT', 'EMI', 'SIP', 'GST', 'PDF', 'UPI', 'ATM', 'US', 'UK', 'AI', 'SME']);

/**
 * Turn a login handle into a human name: `aarav.menon.db241` → `Aarav Menon`.
 * Numeric or generated suffixes (`.db241`) are dropped rather than shown.
 */
export function humanizeHandle(value) {
  let s = String(value || '').trim().replace(/^@/, '');
  if (!s) return '';
  s = s.split('@')[0];
  const raw = s.split(/[._\-+\s]+/).filter(Boolean);
  const useful = raw.filter(p => !/^\d+$/.test(p) && !/\d/.test(p));
  const parts = useful.length ? useful : raw.slice(0, 1);
  return parts
    .map(p => (NAME_ACRONYMS.has(p.toUpperCase())
      ? p.toUpperCase()
      : p.charAt(0).toUpperCase() + p.slice(1).toLowerCase()))
    .join(' ');
}

function looksLikeHandle(value) {
  return /[@._-]/.test(value) || /\d/.test(value);
}

/**
 * Display name for a person. Never surfaces handles or raw IDs.
 * A real `name` / `displayName` is trusted verbatim; a username/handle is
 * humanised (`aarav.menon.db241` → `Aarav Menon`).
 */
export function displayName(person, fallback = 'Member') {
  if (!person) return fallback;
  let raw = '';
  let fromHandle = false;
  if (typeof person === 'string') {
    raw = person;
    fromHandle = true;
  } else {
    raw = person.name || person.displayName || '';
    if (!raw) {
      raw = person.username || person.userName || person.handle || '';
      fromHandle = true;
    }
  }
  let clean = String(raw || '').replace(/^@/, '').trim();
  if (!clean) return fallback;
  // A raw UUID is not a name.
  if (UUID_RE.test(clean)) return fallback;
  if (clean.includes('@')) { clean = clean.split('@')[0]; fromHandle = true; }
  if (fromHandle || looksLikeHandle(clean)) {
    const human = humanizeHandle(clean);
    return human || fallback;
  }
  return clean;
}

/** The current user is always "You" in activity sentences. */
export function isMe(person, userId) {
  if (!person || !userId) return false;
  if (typeof person === 'string') return person === userId;
  return person.userId === userId || person.id === userId;
}

/**
 * Replace handle-shaped words inside generated transaction titles
 * ("Paid aarav.menon.db241") with human names. A lowercase dotted token is a
 * login handle, not a brand; title-casing it reads better either way.
 */
export function humanizeNamesIn(value) {
  return String(value || '').replace(/[@a-z0-9][a-z0-9._-]*[._@][a-z0-9._-]+/gi, (token) => {
    if (!/[._@]/.test(token)) return token;
    if (!/^[a-z0-9@._-]+$/i.test(token)) return token;
    if (/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(token)) return token; // an email, not a handle
    const human = humanizeHandle(token);
    return human || token;
  });
}

export function initials(name) {
  const parts = displayName(name, 'U').split(/\s+/).filter(Boolean);
  if (!parts.length) return 'U';
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

const AVATAR_TONES = 6;
function toneFor(name) {
  const s = String(name || '');
  let hash = 0;
  for (let i = 0; i < s.length; i++) hash = (hash * 31 + s.charCodeAt(i)) >>> 0;
  return (hash % AVATAR_TONES) + 1;
}

export function avatar(name, size = 32, profilePicUrl = null) {
  const label = displayName(name, 'User');
  if (profilePicUrl) {
    return `<img src="${esc(profilePicUrl)}" alt="${esc(label)}" class="avatar" style="width:${size}px;height:${size}px;border-radius:999px;vertical-align:middle;object-fit:cover;">`;
  }
  return `<span class="avatar avatar--tone-${toneFor(label)}" style="width:${size}px;height:${size}px;font-size:${Math.max(11, Math.round(size * 0.38))}px" aria-hidden="true">${esc(initials(label))}</span>`;
}

/* ── Currencies ─────────────────────────────────────────────────────────── */
export const CURRENCY_OPTIONS = [
  'INR', 'USD', 'EUR', 'GBP', 'AED', 'AUD', 'CAD', 'CHF', 'CNY', 'JPY',
  'SGD', 'HKD', 'NZD', 'SEK', 'ZAR', 'BRL', 'MXN', 'THB', 'MYR', 'PHP',
];

const CURRENCY_NAMES = {
  INR: 'Indian rupee', USD: 'US dollar', EUR: 'Euro', GBP: 'British pound',
  AED: 'UAE dirham', AUD: 'Australian dollar', CAD: 'Canadian dollar',
  CHF: 'Swiss franc', CNY: 'Chinese yuan', JPY: 'Japanese yen',
  SGD: 'Singapore dollar', HKD: 'Hong Kong dollar', NZD: 'New Zealand dollar',
  SEK: 'Swedish krona', ZAR: 'South African rand', BRL: 'Brazilian real',
  MXN: 'Mexican peso', THB: 'Thai baht', MYR: 'Malaysian ringgit',
  PHP: 'Philippine peso',
};

export function currencySymbol(code) {
  try {
    return (0).toLocaleString('en-IN', { style: 'currency', currency: code, maximumFractionDigits: 0 })
      .replace(/[\d\s.,]/g, '') || code;
  } catch { return code; }
}

export function currencyLabel(code) {
  const c = String(code || 'INR').toUpperCase();
  return `${currencySymbol(c)} ${c} · ${CURRENCY_NAMES[c] || c}`;
}

export function currencyOptionHtml(selected = 'INR') {
  const current = String(selected || 'INR').toUpperCase();
  const list = CURRENCY_OPTIONS.includes(current) ? CURRENCY_OPTIONS : [current, ...CURRENCY_OPTIONS];
  return list.map(c => `<option value="${c}" ${c === current ? 'selected' : ''}>${esc(currencyLabel(c))}</option>`).join('');
}

/* ── Money ──────────────────────────────────────────────────────────────── */
/**
 * One money formatter for the whole app: Indian grouping, two decimals,
 * a true minus sign before the symbol.
 *   formatMoney(-6327.59) -> −₹6,327.59
 */
export function formatMoney(amount, currency = 'INR', { compact = false, decimals = null } = {}) {
  const num = Number(amount);
  const value = Number.isFinite(num) ? num : 0;
  const cur = String(currency || 'INR').toUpperCase();
  const digits = compact ? 0 : (decimals != null ? decimals : 2);
  let body;
  try {
    body = new Intl.NumberFormat('en-IN', {
      style: 'currency',
      currency: cur,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(Math.abs(value));
  } catch {
    body = `${cur} ${Math.abs(value).toLocaleString('en-IN', {
      minimumFractionDigits: digits, maximumFractionDigits: digits,
    })}`;
  }
  return value < 0 ? `−${body}` : body;
}

/** Backwards-compatible alias used across the views. */
export function formatCurrency(n, currency = 'INR') {
  return formatMoney(n, currency);
}

/** Axis/annotation format: ₹0, ₹50K, ₹1.2L, ₹1.1Cr. */
export function formatCompact(amount, currency = 'INR') {
  const num = Number(amount);
  if (!Number.isFinite(num)) return formatMoney(0, currency, { compact: true });
  const abs = Math.abs(num);
  const sign = num < 0 ? '−' : '';
  const sym = currencySymbol(currency);
  const trim = (n) => String(Number(n.toFixed(n >= 100 ? 0 : 1)));
  if (abs >= 1e7) return `${sign}${sym}${trim(abs / 1e7)}Cr`;
  if (abs >= 1e5) return `${sign}${sym}${trim(abs / 1e5)}L`;
  if (abs >= 1e3) return `${sign}${sym}${trim(abs / 1e3)}K`;
  return `${sign}${sym}${Math.round(abs)}`;
}

/** Signed amount for a ledger row: +income / −expense. */
export function formatSigned(n, currency = 'INR', type = 'EXPENSE') {
  const value = Math.abs(Number(n) || 0);
  return formatMoney(type === 'INCOME' ? value : -value, currency);
}

/** Amount markup: right-aligned, tabular, with an optional secondary line. */
export function amountHtml(value, {
  currency = 'INR', type = 'EXPENSE', size = '', secondary = '', signed = true,
} = {}) {
  const n = Number(value) || 0;
  const isIncome = type === 'INCOME';
  const shown = signed
    ? formatMoney(isIncome ? Math.abs(n) : -Math.abs(n), currency)
    : formatMoney(n, currency);
  const cls = ['amount', isIncome ? 'amount--pos' : '', size ? `amount--${size}` : ''].filter(Boolean).join(' ');
  return `
    <span class="${cls}">
      <span class="amount-value">${esc(shown)}</span>
      ${secondary ? `<span class="amount-secondary">${esc(secondary)}</span>` : ''}
    </span>`;
}

/**
 * Show an amount in the user's default currency.
 * The entries API returns `amountDefault` (converted server-side from cached
 * ECB rates) when the row's currency differs from the user's default, so the
 * converted figure is primary and the original is a muted secondary line.
 * When the backend could not convert, the original amount is shown as-is.
 */
export function displayAmountFor(record, defaultCurrency = 'INR') {
  const target = String(defaultCurrency || 'INR').toUpperCase();
  const cur = String(record?.currency || target).toUpperCase();
  const original = Math.abs(Number(record?.amount) || 0);
  const converted = record?.amountDefault != null
    ? Number(record.amountDefault)
    : (record?.convertedAmount != null ? Number(record.convertedAmount) : null);
  if (cur === target) return { amount: original, currency: cur, secondary: '' };
  if (Number.isFinite(converted)) {
    return {
      amount: converted,
      currency: target,
      secondary: `${formatMoney(original, cur)} originally`,
    };
  }
  return { amount: original, currency: cur, secondary: 'not converted' };
}

/* ── Categories ─────────────────────────────────────────────────────────── */
const CATEGORY_ACRONYMS = new Set(['OTT', 'EMI', 'SIP', 'GST', 'PDF', 'UPI', 'ATM', 'US', 'UK']);
export function formatCategory(cat) {
  if (!cat) return '—';
  const words = String(cat).replace(/_/g, ' ').toLowerCase().split(/\s+/).filter(Boolean);
  const label = words.map(w => (CATEGORY_ACRONYMS.has(w.toUpperCase()) ? w.toUpperCase() : w)).join(' ');
  return label.charAt(0).toUpperCase() + label.slice(1);
}

/**
 * Generated text arrives in Title Case. Headings in the UI are sentence case:
 * "Emergency Fund Is Behind" → "Emergency fund is behind".
 * Acronyms and all-caps words are left alone.
 */
export function toSentenceCase(value) {
  const s = String(value || '').trim();
  if (!s) return s;
  const lowered = s.replace(/\b([A-Z][a-z]+)\b/g, w => {
    if (NAME_ACRONYMS.has(w.toUpperCase())) return w.toUpperCase();
    return w.toLowerCase();
  });
  return lowered.charAt(0).toUpperCase() + lowered.slice(1);
}

let _categoryTokenSet = null;
function knownCategoryToken(token) {
  const t = String(token || '').toUpperCase();
  if (!_categoryTokenSet) _categoryTokenSet = new Set([...EXPENSE_CATS, ...INCOME_CATS]);
  if (_categoryTokenSet.has(t)) return true;
  // Underscored ALL-CAPS tokens are category-shaped even if not in our list.
  return /^[A-Z][A-Z_]{2,}$/.test(t) && !NAME_ACRONYMS.has(t) && t.includes('_');
}

/**
 * Strip system-speak and shouting from generated copy, in the UI layer:
 *   "Auto-generated: monthly subscription"            → "Monthly subscription"
 *   "Auto-generated (Aggregated 2 missed periods): …"  → "… · 2 charges caught up"
 *   "RESTAURANTS changed by -77.7%"                    → "restaurants changed by -77.7%"
 */
export function humanizeSystemText(value) {
  let s = String(value || '');
  let caughtUp = null;
  s = s.replace(/Auto-generated\s*\(Aggregated\s+(\d+)\s+missed\s+periods?\)\s*:\s*/gi, (_, n) => {
    caughtUp = n;
    return '';
  });
  s = s.replace(/Auto-generated\s*:\s*/gi, '');
  // ALL-CAPS category names inside generated sentences become normal words.
  s = s.replace(/\b[A-Z][A-Z_]{2,}\b/g, (token) => {
    if (!knownCategoryToken(token)) return token;
    const label = formatCategory(token);
    return label.charAt(0).toLowerCase() + label.slice(1);
  });
  s = s.replace(/\s{2,}/g, ' ').trim();
  if (caughtUp) s += ` · ${caughtUp} charges caught up`;
  return toSentenceCase(s);
}

/** Remove currency amounts from generated text so they cannot contradict our selector. */
export function stripAmounts(value) {
  return String(value || '')
    .replace(/(?:₹|Rs\.?|\$|€|£)\s?-?[\d,]+(?:\.\d+)?(?:\s?(?:K|L|Cr|lakh|crore))?/gi, '')
    .replace(/\s{2,}/g, ' ')
    .replace(/\s+([.,;:!?])/g, '$1')
    .replace(/^[\s,;:–-]+|[\s,;:–-]+$/g, '')
    .trim();
}

/* ── Dates ──────────────────────────────────────────────────────────────── */
function asDate(value) {
  if (!value) return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  const s = String(value);
  const d = /^\d{4}-\d{2}-\d{2}$/.test(s) ? new Date(`${s}T00:00:00`) : new Date(s);
  return Number.isNaN(d.getTime()) ? null : d;
}

function isoDayDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 2 Nov 2026 */
export function formatDate(iso) {
  const d = asDate(iso);
  if (!d) return '—';
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** Mon 2 Nov */
export function formatDay(iso) {
  const d = asDate(iso);
  if (!d) return '—';
  return d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
}

/** Today / Yesterday / Mon 2 Nov */
export function formatDayLabel(iso) {
  const d = asDate(iso);
  if (!d) return 'Unknown';
  const key = isoDayDate(d);
  const today = new Date();
  if (key === isoDayDate(today)) return 'Today';
  if (key === isoDayDate(new Date(today.getTime() - 86400000))) return 'Yesterday';
  return formatDay(d);
}

/** Fri 5 Dec · in 26 days */
export function formatUpcoming(iso, daysUntil) {
  const d = asDate(iso);
  if (!d) return '—';
  const days = daysUntil != null ? Number(daysUntil) : Math.round((d.getTime() - Date.now()) / 86400000);
  const day = d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
  if (days < 0) return `${day} · overdue`;
  if (days === 0) return `${day} · today`;
  if (days === 1) return `${day} · tomorrow`;
  return `${day} · in ${days} days`;
}

/** Relative first, absolute second: "Tomorrow · Thu 5 Nov". */
export function formatDue(iso, daysUntil) {
  const d = asDate(iso);
  if (!d) return '—';
  const days = daysUntil != null ? Number(daysUntil) : Math.round((d.getTime() - Date.now()) / 86400000);
  const absolute = d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
  if (days < 0) return `Overdue · ${absolute}`;
  if (days === 0) return `Today · ${absolute}`;
  if (days === 1) return `Tomorrow · ${absolute}`;
  return `In ${days} days · ${absolute}`;
}

/** Absolute date for a tooltip on any relative date label. */
export function dateTooltip(iso) {
  return formatDate(iso);
}

/** Month name only, adding the year when it is not the current one. */
export function formatMonth(label) {
  const m = /^(\d{4})-(\d{2})$/.exec(String(label || ''));
  if (!m) return String(label || '');
  const date = new Date(Number(m[1]), Number(m[2]) - 1, 1);
  const month = date.toLocaleDateString('en-IN', { month: 'short' });
  return date.getFullYear() === new Date().getFullYear() ? month : `${month} ${date.getFullYear()}`;
}

export function relativeTime(iso) {
  const date = asDate(iso);
  if (!date) return '';
  const minutes = Math.round((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 1440) return `${Math.round(minutes / 60)} hr ago`;
  return formatDate(date);
}

/* ── Theme ──────────────────────────────────────────────────────────────── */
const THEME_KEY = 'pfa_theme';
const DARK_QUERY = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

export function getThemePreference() {
  try { return localStorage.getItem(THEME_KEY) || 'system'; } catch { return 'system'; }
}

export function resolvedTheme(pref = getThemePreference()) {
  if (pref === 'dark' || pref === 'light') return pref;
  return DARK_QUERY && DARK_QUERY.matches ? 'dark' : 'light';
}

export function applyTheme(pref = getThemePreference()) {
  const resolved = resolvedTheme(pref);
  document.documentElement.setAttribute('data-theme', resolved);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute('content', resolved === 'dark' ? '#0D1412' : '#F4F5F3');
  return resolved;
}

export function setThemePreference(pref) {
  const value = ['system', 'dark', 'light'].includes(pref) ? pref : 'system';
  try { localStorage.setItem(THEME_KEY, value); } catch { /* storage unavailable */ }
  applyTheme(value);
  window.dispatchEvent(new CustomEvent('theme-change', { detail: { preference: value } }));
  return value;
}

if (DARK_QUERY && DARK_QUERY.addEventListener) {
  DARK_QUERY.addEventListener('change', () => {
    if (getThemePreference() === 'system') applyTheme('system');
  });
}

/* ── Layout helpers ─────────────────────────────────────────────────────── */
export function pageHeader(title, subtitle, actionsHtml = '') {
  return `
    <div class="page-header">
      <div class="page-header-text">
        <h1>${esc(title)}</h1>
        ${subtitle ? `<p>${esc(subtitle)}</p>` : ''}
      </div>
      ${actionsHtml ? `<div class="page-header-actions">${actionsHtml}</div>` : ''}
    </div>`;
}

export function emptyState(iconName, title, subtitle = '', actionHtml = '') {
  return `
    <div class="empty-state">
      <div class="empty-icon">${icon(iconName, 'lg')}</div>
      <h3>${esc(title)}</h3>
      ${subtitle ? `<p>${esc(subtitle)}</p>` : ''}
      ${actionHtml ? `<div class="empty-action">${actionHtml}</div>` : ''}
    </div>`;
}

export function alertBanner(message, level = 'warning', iconName = null) {
  const map = {
    error: ['neg', 'alert-circle'],
    warning: ['warn', 'alert-triangle'],
    info: ['brand', 'info'],
    success: ['pos', 'check-circle'],
  };
  const [tone, defaultIcon] = map[level] || map.info;
  return `
    <div class="alert-banner alert-banner--${tone}" role="alert">
      <span class="alert-banner-icon">${icon(iconName || defaultIcon, 'sm')}</span>
      <span>${message}</span>
    </div>`;
}

/* ── Components ─────────────────────────────────────────────────────────── */
export function chip(text, variant = 'neutral') {
  return `<span class="chip chip--${variant}">${esc(text)}</span>`;
}

export function badge(text, variant = 'neutral') {
  const map = { income: 'pos', expense: 'neg', success: 'pos', warning: 'warn', info: 'neutral' };
  return chip(text, map[variant] || 'neutral');
}

export function typeBadge(type) {
  return `<span class="badge badge-${type === 'INCOME' ? 'income' : 'expense'}">${esc(type === 'INCOME' ? 'Income' : 'Expense')}</span>`;
}

export function budgetStatusBadge(status) {
  if (status === 'EXCEEDED') return chip('Over budget', 'neg');
  if (status === 'WARNING') return chip('Close to limit', 'warn');
  return chip('On track', 'pos');
}

/** Segmented control. Keyboard: arrow keys move the selection. */
export function segmentedControl(name, options, selected, ariaLabel = '') {
  const id = `seg-${name}`;
  return `
    <div class="segmented" id="${id}" role="tablist" aria-label="${esc(ariaLabel || name)}">
      ${options.map(o => `
        <button type="button" role="tab" class="segmented-item ${o.id === selected ? 'is-active' : ''}"
          data-value="${esc(o.id)}" aria-selected="${o.id === selected ? 'true' : 'false'}"
          tabindex="${o.id === selected ? '0' : '-1'}">${esc(o.label)}</button>`).join('')}
    </div>`;
}

export function wireSegmented(root, onChange) {
  const seg = root.querySelector('.segmented');
  if (!seg) return;
  const items = [...seg.querySelectorAll('.segmented-item')];
  const select = (btn, focus = false) => {
    items.forEach(b => {
      const active = b === btn;
      b.classList.toggle('is-active', active);
      b.setAttribute('aria-selected', active ? 'true' : 'false');
      b.tabIndex = active ? 0 : -1;
    });
    if (focus) btn.focus();
    onChange(btn.dataset.value);
  };
  items.forEach((btn, i) => {
    btn.onclick = () => select(btn);
    btn.onkeydown = (e) => {
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
        e.preventDefault(); select(items[(i + 1) % items.length], true);
      } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
        e.preventDefault(); select(items[(i - 1 + items.length) % items.length], true);
      }
    };
  });
}

export function progressBar(pct, color = 'var(--pos)', showLabel = false, { expected = null } = {}) {
  const width = Math.min(Math.max(Number(pct) || 0, 0), 100);
  return `
    <div class="progress-wrap">
      <div class="progress-track" role="progressbar" aria-valuenow="${width.toFixed(0)}" aria-valuemin="0" aria-valuemax="100">
        <div class="progress-fill" style="width:${width}%;background:${color}"></div>
        ${expected != null ? `<span class="progress-tick" style="left:${Math.min(Math.max(Number(expected) || 0, 0), 100)}%" title="Expected by today"></span>` : ''}
      </div>
      ${showLabel ? `<span class="progress-label">${width.toFixed(0)}%</span>` : ''}
    </div>`;
}

export function budgetStatusColor(status) {
  if (status === 'EXCEEDED') return 'var(--neg)';
  if (status === 'WARNING') return 'var(--warn)';
  return 'var(--pos)';
}

export function dataField(label, value) {
  return `
    <div class="data-field">
      <span class="data-field-label">${esc(label)}</span>
      <span class="data-field-value">${esc(value ?? '—')}</span>
    </div>`;
}

/* ── Insights panel ─────────────────────────────────────────────────────── */
const AI_TYPE_CONFIG = {
  WARNING:     { label: 'Attention',   icon: 'warning',  tone: 'is-warning' },
  ACHIEVEMENT: { label: 'Win',         icon: 'award',    tone: 'is-good' },
  TIP:         { label: 'Suggestion',  icon: 'sparkles', tone: '' },
  INFO:        { label: 'Insight',     icon: 'info',     tone: '' },
};

function aiTypeConfig(type) {
  return AI_TYPE_CONFIG[(type || 'INFO').toUpperCase()] || AI_TYPE_CONFIG.INFO;
}

function normalizeInsight(ins) {
  if (!ins || (!ins.title && !ins.message)) return null;
  const type = (ins.type || 'INFO').toUpperCase();
  let priority = Number(ins.priority);
  if (!Number.isFinite(priority) || priority < 1) priority = 3;
  if (priority > 5) priority = 5;
  return {
    title: ins.title || 'Insight',
    message: ins.message || '',
    type: AI_TYPE_CONFIG[type] ? type : 'INFO',
    priority,
  };
}

/**
 * Merge insights that repeat the same subject. Comparing the first two
 * meaningful words catches "Emergency fund is behind" and "Emergency fund
 * needs a higher transfer" without merging unrelated advice.
 */
function dedupeInsights(insights) {
  const seen = new Map();
  for (const ins of insights) {
    const words = ins.title.toLowerCase().replace(/[^a-z0-9% ]/g, '').split(/\s+/)
      .filter(w => w.length > 3 || /^\d/.test(w));
    const key = words.slice(0, 2).join(' ') || ins.title.toLowerCase().slice(0, 40);
    const existing = seen.get(key);
    if (!existing) seen.set(key, ins);
    else if (ins.priority < existing.priority) seen.set(key, ins);
  }
  return [...seen.values()];
}

function aiInsightHtml(ins) {
  const cfg = aiTypeConfig(ins.type);
  const action = ins.type === 'WARNING'
    ? { target: 'goals', label: 'Review goals and budgets' }
    : ins.type === 'ACHIEVEMENT'
      ? { target: 'goals', label: 'Open goals' }
      : { target: 'analytics', label: 'Open analytics' };
  // Generated text is Title Case and shouts category names; fix that here and
  // keep figures out of it so they cannot contradict the totals selector.
  const title = toSentenceCase(ins.title);
  const rawMessage = humanizeSystemText(ins.message);
  // If a supporting line carries a currency figure, drop the line: the hero
  // and every list already show the figure from the shared selector. The
  // backend prompt avoids amounts now; this guards cached or older copy.
  const message = /(?:₹|Rs\.?|\$|€|£)\s?-?\d/i.test(ins.message || '') ? '' : stripAmounts(rawMessage);
  return `
    <article class="advisor-insight ${cfg.tone}" data-type="${ins.type}" data-priority="${ins.priority}">
      <span class="advisor-insight-icon">${icon(cfg.icon, 'sm')}</span>
      <div class="advisor-insight-body">
        <strong class="advisor-insight-title">${esc(title)}</strong>
        ${message ? `<p class="advisor-insight-message">${esc(message)}</p>` : ''}
        <button type="button" class="insight-action" onclick="window.navigateTo('${action.target}')">${esc(action.label)}</button>
      </div>
    </article>`;
}

function aiNoticeHtml(iconName, title, message, tone = '') {
  return `
    <div class="advisor-notice ${tone}">
      <span class="advisor-notice-icon">${icon(iconName)}</span>
      <div>
        <strong>${esc(title)}</strong>
        <p>${esc(message)}</p>
      </div>
    </div>`;
}

export function aiPanelHtml(prefix) {
  const skeleton = Array.from({ length: 3 }, () => `
    <div class="advisor-insight advisor-skeleton" aria-hidden="true">
      <div class="skeleton advisor-skeleton-line advisor-skeleton-line--short"></div>
      <div class="skeleton advisor-skeleton-line"></div>
    </div>`).join('');
  return `
    <section class="advisor-card" aria-labelledby="${prefix}-ai-title">
      <div class="advisor-header">
        <h3 class="section-title attention-head" id="${prefix}-ai-title">Insights</h3>
        <div class="advisor-header-meta">
          <span class="advisor-updated" id="${prefix}-ai-meta"></span>
          <button type="button" class="btn btn-icon btn-ghost btn-sm advisor-refresh" id="${prefix}-ai-refresh" title="Refresh insights" aria-label="Refresh insights">
            ${icon('refresh', 'sm')}
          </button>
        </div>
      </div>
      <p id="${prefix}-ai-summary" class="ai-summary">Analyzing your transactions…</p>
      <div id="${prefix}-ai-insights-list" class="advisor-grid" aria-live="polite" aria-busy="true">
        ${skeleton}
      </div>
      <div id="${prefix}-ai-goals" class="advisor-goals" hidden></div>
    </section>`;
}

export function renderAiData(prefix, ai, onRefresh, { scopeLabel = '' } = {}) {
  const elSummary = document.getElementById(`${prefix}-ai-summary`);
  const elMeta = document.getElementById(`${prefix}-ai-meta`);
  const elList = document.getElementById(`${prefix}-ai-insights-list`);
  const elRefresh = document.getElementById(`${prefix}-ai-refresh`);
  const elTitle = document.getElementById(`${prefix}-ai-title`);

  if (elRefresh && typeof onRefresh === 'function') elRefresh.onclick = () => onRefresh();
  if (!elList) return;
  elList.setAttribute('aria-busy', 'false');

  if (!ai) {
    if (elSummary) elSummary.textContent = 'Insights are unavailable right now.';
    elList.innerHTML = aiNoticeHtml('info', 'No insights available', 'Try refreshing in a moment.');
    return;
  }

  if (ai.error) {
    if (elSummary) elSummary.textContent = 'The advisor could not load your insights.';
    if (elMeta) elMeta.textContent = 'Not updated';
    elList.innerHTML = aiNoticeHtml('alert-triangle', 'Could not load insights', ai.error, 'advisor-notice--error');
    return;
  }

  const TYPE_ORDER = { WARNING: 0, INFO: 1, TIP: 1, ACHIEVEMENT: 2 };
  const sorted = dedupeInsights((ai.insights || [])
    .map(normalizeInsight)
    .filter(Boolean)
    .sort((a, b) => {
      const order = (TYPE_ORDER[a.type] ?? 1) - (TYPE_ORDER[b.type] ?? 1);
      return order !== 0 ? order : a.priority - b.priority;
    }));
  // Keep at most three, but never drop every piece of good news for a filler
  // neutral item: warnings first, then neutral, then a win if one exists.
  let picked = sorted.slice(0, 3);
  if (sorted.length > picked.length && !picked.some(i => i.type === 'ACHIEVEMENT')) {
    const win = sorted.find(i => i.type === 'ACHIEVEMENT');
    if (win) picked = [...picked.slice(0, 2), win];
  }
  const insights = picked;

  if (elSummary) {
    // Show the scope instead of a generated paragraph that repeats the hero
    // totals (and may disagree with the selector).
    elSummary.textContent = scopeLabel ? `Based on ${scopeLabel}` : (ai.summary || 'Here is what your recent activity suggests.');
    elSummary.hidden = insights.length === 0 && !scopeLabel;
  }
  if (elMeta) {
    const when = ai.generatedAt ? relativeTime(ai.generatedAt) : '';
    elMeta.textContent = when ? `Updated ${when}` : '';
  }
  if (elTitle) {
    const warnings = insights.filter(i => i.type === 'WARNING').length;
    elTitle.innerHTML = warnings > 0
      ? `Insights <span class="chip chip--warn chip--inline" title="${warnings} warning${warnings === 1 ? '' : 's'}">${warnings}</span>`
      : 'Insights';
  }

  if (!insights.length) {
    elList.innerHTML = aiNoticeHtml('check-circle', 'All clear',
      'No concerns found in your recent activity.');
    return;
  }

  elList.innerHTML = insights.map(aiInsightHtml).join('');
}

/* ── Modals ─────────────────────────────────────────────────────────────── */
let lastFocused = null;

export function openModal(title, bodyHtml, { onSubmit, submitLabel = 'Save', size = '' } = {}) {
  lastFocused = document.activeElement;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal ${size}" role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <div class="modal-header">
        <h2 id="modal-title">${esc(title)}</h2>
        <button type="button" class="btn btn-icon btn-ghost modal-close" aria-label="Close">${icon('close', 'sm')}</button>
      </div>
      <div class="modal-body">${bodyHtml}</div>
    </div>`;
  document.body.appendChild(overlay);

  const close = () => {
    overlay.remove();
    document.removeEventListener('keydown', onKeydown);
    if (lastFocused && typeof lastFocused.focus === 'function') lastFocused.focus();
  };
  const focusables = () => [...overlay.querySelectorAll(
    'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )].filter(el => el.offsetParent !== null || el === document.activeElement);

  const onKeydown = (e) => {
    if (e.key === 'Escape') { e.preventDefault(); close(); return; }
    if (e.key !== 'Tab') return;
    const list = focusables();
    if (!list.length) return;
    const first = list[0], last = list[list.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };
  document.addEventListener('keydown', onKeydown);

  overlay.querySelector('.modal-close').onclick = close;
  overlay.onclick = (e) => { if (e.target === overlay) close(); };

  const cancelBtn = overlay.querySelector('[data-modal-cancel]');
  if (cancelBtn) cancelBtn.onclick = close;

  const form = overlay.querySelector('form');
  if (form && onSubmit) {
    form.onsubmit = async (e) => {
      e.preventDefault();
      const submitBtn = form.querySelector('[type="submit"]');
      if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.dataset.originalText = submitBtn.textContent;
        submitBtn.innerHTML = '<span class="spinner"></span>';
      }
      try {
        await onSubmit(form, overlay);
        close();
      } catch (err) {
        if (submitBtn) {
          submitBtn.disabled = false;
          submitBtn.textContent = submitBtn.dataset.originalText || submitLabel;
        }
        const { toast } = await import('./api.js');
        toast(err.message || 'Something went wrong', 'error');
      }
    };
  }

  if (window.flatpickr) {
    flatpickr(overlay.querySelectorAll('input[type="date"]'), {
      dateFormat: 'Y-m-d',
      allowInput: true,
      altInput: true,
      altFormat: 'j M Y',
      disableMobile: true,
    });
  }

  if (window.TomSelect) {
    overlay.querySelectorAll('select.form-select').forEach(sel => {
      if (sel.tomselect) return;
      new TomSelect(sel, { create: false, controlInput: null, maxOptions: 200 });
    });
  }

  setTimeout(() => {
    const firstField = overlay.querySelector('input:not([type="hidden"]), textarea, select');
    (firstField || overlay.querySelector('.modal-close'))?.focus();
  }, 20);

  return { overlay, close };
}

export function confirmModal(title, message, confirmText = 'Confirm', cancelText = 'Cancel') {
  return new Promise((resolve) => {
    const { overlay, close } = openModal(title, `
      <p class="modal-lead">${esc(message)}</p>
      <div class="modal-actions">
        <button type="button" class="btn btn-secondary" id="confirm-cancel-btn">${esc(cancelText)}</button>
        <button type="button" class="btn btn-primary" id="confirm-ok-btn">${esc(confirmText)}</button>
      </div>
    `);

    const done = (value) => { close(); resolve(value); };
    overlay.querySelector('#confirm-cancel-btn').onclick = () => done(false);
    overlay.querySelector('#confirm-ok-btn').onclick = () => done(true);
    overlay.querySelector('.modal-close').onclick = () => done(false);
    overlay.onclick = (e) => { if (e.target === overlay) done(false); };
  });
}

export function modalActions(cancelLabel = 'Cancel', submitLabel = 'Save') {
  return `
    <div class="modal-actions">
      <button type="button" class="btn btn-secondary" data-modal-cancel>${esc(cancelLabel)}</button>
      <button type="submit" class="btn btn-primary">${esc(submitLabel)}</button>
    </div>`;
}

/* ── Category options ───────────────────────────────────────────────────── */
export const EXPENSE_CATS = [
  'RENT', 'HOME_LOAN_EMI', 'HOME_MAINTENANCE', 'PROPERTY_TAX', 'ELECTRICITY', 'WATER', 'GAS', 'INTERNET', 'MOBILE_PHONE', 'OTT_SUBSCRIPTIONS',
  'GROCERIES', 'RESTAURANTS', 'FOOD_DELIVERY', 'COFFEE_AND_SNACKS',
  'FUEL', 'PUBLIC_TRANSPORT', 'CAB_AND_AUTO', 'VEHICLE_EMI', 'VEHICLE_MAINTENANCE', 'PARKING_AND_TOLLS', 'FLIGHT_AND_TRAIN',
  'DOCTOR_AND_CLINIC', 'MEDICINES', 'HEALTH_INSURANCE', 'GYM_AND_FITNESS', 'MENTAL_WELLNESS',
  'TUITION_AND_FEES', 'BOOKS_AND_COURSES', 'COACHING', 'STUDENT_LOAN_EMI',
  'CLOTHING', 'ELECTRONICS', 'HOME_APPLIANCES', 'PERSONAL_CARE', 'GIFTS_GIVEN',
  'MOVIES_AND_EVENTS', 'GAMING', 'SPORTS_AND_HOBBIES', 'BOOKS_AND_MAGAZINES', 'TRAVEL_VACATION', 'HOTEL_AND_STAYS',
  'LIFE_INSURANCE', 'VEHICLE_INSURANCE', 'CREDIT_CARD_PAYMENT', 'LOAN_REPAYMENT', 'MUTUAL_FUNDS_SIP', 'STOCKS_AND_TRADING', 'CRYPTO', 'EMERGENCY_FUND', 'FIXED_DEPOSIT',
  'CHARITY_AND_DONATIONS', 'TAXES', 'FINES_AND_PENALTIES', 'PETS', 'CHILDCARE', 'ELDER_CARE', 'GOAL', 'SETTLEMENT', 'OTHERS'
];

export const INCOME_CATS = [
  'SALARY', 'FREELANCE', 'BUSINESS', 'INVESTMENTS', 'RENTAL_INCOME', 'DIVIDENDS', 'INTEREST', 'BONUS', 'PENSION', 'GOVT_BENEFITS', 'CASHBACK_REWARDS', 'GIFTS_RECEIVED', 'TAX_REFUND', 'SIDE_HUSTLE', 'OTHER_INCOME'
];

export function categoryOptions(cats, selected) {
  return cats.map(c =>
    `<option value="${c}" ${selected === c ? 'selected' : ''}>${formatCategory(c)}</option>`
  ).join('');
}

export function categoryIconHtml(cat, size = 'sm') {
  return `<span class="row-icon">${icon(categoryIcon(cat), size)}</span>`;
}

export function setupCategorySearch(inputId, selectId) {
  const input = document.getElementById(inputId);
  const selectElement = document.getElementById(selectId);
  if (!selectElement) return;
  if (input) input.style.display = 'none';
  if (window.TomSelect && !selectElement.tomselect) {
    new TomSelect(selectElement, {
      create: false,
      sortField: { field: 'text', direction: 'asc' },
      placeholder: 'Search category…',
      maxOptions: 100,
    });
  }
}

export function openReceiptLightbox(url) {
  const isPdf = String(url).toLowerCase().endsWith('.pdf');
  if (isPdf) {
    window.open(url, '_blank');
  } else {
    openModal('Receipt', `<div class="receipt-lightbox"><img src="${esc(url)}" alt="Receipt"></div>`, { size: 'lg' });
  }
}
window.openReceiptLightbox = openReceiptLightbox;

/* ── Skeletons ──────────────────────────────────────────────────────────── */
export function skeletonKpiRow(count = 4) {
  return `<div class="figures">${Array.from({ length: count }, () =>
    `<div class="figure"><div class="skeleton skeleton-label"></div><div class="skeleton skeleton-value"></div></div>`
  ).join('')}</div>`;
}

export function skeletonChart(height = 280) {
  return `<div class="skeleton skeleton-chart" style="height:${height}px"></div>`;
}

export function skeletonRows(count = 5) {
  return Array.from({ length: count }, () => `
    <div class="skeleton-row">
      <div class="skeleton skeleton-avatar"></div>
      <div class="skeleton-row-text">
        <div class="skeleton skeleton-line"></div>
        <div class="skeleton skeleton-line skeleton-line--short"></div>
      </div>
      <div class="skeleton skeleton-amount"></div>
    </div>`).join('');
}
