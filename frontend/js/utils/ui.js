/* ═══════════════════════════════════════════════════════════════════════════
   ui.js — Shared UI helpers, formatters, and component builders
   ═══════════════════════════════════════════════════════════════════════════ */
import { icon } from './icons.js';

export function esc(s) {
  const d = document.createElement('div');
  d.textContent = s ?? '';
  return d.innerHTML;
}

export function avatar(name, size = 32, profilePicUrl = null) {
  if (profilePicUrl) {
    return `<img src="${profilePicUrl}" alt="${esc(name)}" class="avatar" style="width:${size}px; height:${size}px; border-radius:50%; vertical-align:middle; object-fit:cover;">`;
  }
  const encoded = encodeURIComponent(name || 'User');
  const url = `https://ui-avatars.com/api/?name=${encoded}&background=random&color=fff&rounded=true&size=${size}&bold=true`;
  return `<img src="${url}" alt="${esc(name)}" class="avatar" style="width:${size}px; height:${size}px; border-radius:50%; vertical-align:middle; object-fit:cover;">`;
}

/** Currencies the backend exchange-rate feed (ECB/Frankfurter) supports. */
export const CURRENCY_OPTIONS = [
  'INR', 'USD', 'EUR', 'GBP', 'AED', 'AUD', 'CAD', 'CHF', 'CNY', 'JPY',
  'SGD', 'HKD', 'NZD', 'SEK', 'ZAR', 'BRL', 'MXN', 'THB', 'MYR', 'PHP',
];

export function currencyOptionHtml(selected = 'INR') {
  const current = String(selected || 'INR').toUpperCase();
  const list = CURRENCY_OPTIONS.includes(current) ? CURRENCY_OPTIONS : [current, ...CURRENCY_OPTIONS];
  return list.map(c => `<option value="${c}" ${c === current ? 'selected' : ''}>${c}</option>`).join('');
}

export function formatCurrency(n, currency = 'INR') {  const sym = currency === 'INR' ? '₹' : currency === 'USD' ? '$' : currency === 'EUR' ? '€' : currency === 'GBP' ? '£' : '';
  const num = Number(n || 0);
  if (sym) {
    return sym + num.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
  }
  return `${currency} ${num.toLocaleString('en-IN', { minimumFractionDigits: 2 })}`;
}

export function formatCategory(cat) {
  if (!cat) return '—';
  return cat.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

export function formatDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function healthScoreColor(score) {
  if (score >= 700) return 'var(--accent-g)';
  if (score >= 500) return 'var(--accent-y)';
  return 'var(--accent)';
}

export function budgetStatusColor(status) {
  if (status === 'EXCEEDED') return 'var(--accent)';
  if (status === 'WARNING') return 'var(--accent-y)';
  return 'var(--accent-g)';
}

export function pageHeader(title, subtitle, actionsHtml = '') {
  return `
    <div class="page-header fade-up">
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
      ${actionHtml ? `<div style="margin-top:16px">${actionHtml}</div>` : ''}
    </div>`;
}

/** Renders a coloured alert banner strip.
 *  @param {string} message
 *  @param {'error'|'warning'|'info'|'success'} level
 */
export function alertBanner(message, level = 'warning', iconName = null) {
  const cfg = {
    error:   { bg: 'rgba(239,68,68,0.10)',  border: 'rgba(239,68,68,0.30)',   color: 'var(--accent)',   defaultIcon: 'alert-circle' },
    warning: { bg: 'rgba(245,158,11,0.10)', border: 'rgba(245,158,11,0.30)',  color: 'var(--accent-y)', defaultIcon: 'alert-triangle' },
    info:    { bg: 'rgba(56,189,248,0.10)',  border: 'rgba(56,189,248,0.30)',  color: 'var(--accent-b)', defaultIcon: 'info' },
    success: { bg: 'rgba(34,197,94,0.10)',   border: 'rgba(34,197,94,0.30)',   color: 'var(--accent-g)', defaultIcon: 'check-circle' },
  };
  const { bg, border, color, defaultIcon } = cfg[level] || cfg.info;
  const ic = icon(iconName || defaultIcon, 'sm');
  return `
    <div class="alert-banner" style="background:${bg};border:1px solid ${border};color:${color};" role="alert">
      <span class="alert-banner-icon" style="color:${color}">${ic}</span>
      <span>${message}</span>
    </div>`;
}

/** Renders a KPI delta indicator e.g. ▲ 12.3% vs last month.
 *  @param {number} pct  positive = up, negative = down
 *  @param {string} [label]
 */
export function kpiDelta(pct, label = 'vs last month') {
  if (pct == null || isNaN(pct)) return '';
  const up = pct >= 0;
  const color = up ? 'var(--accent-g)' : 'var(--accent)';
  const arrow = up ? 'arrow-up' : 'arrow-down';
  return `
    <div class="kpi-delta" style="color:${color}">
      ${icon(arrow, 'xs')}
      <span>${Math.abs(pct).toFixed(1)}%</span>
      <span class="kpi-delta-label">${esc(label)}</span>
    </div>`;
}

export function skeletonKpiRow(count = 4) {
  return `<div class="card-grid card-grid-4">${Array.from({ length: count }, () =>
    `<div class="card stat-card skeleton-card"><div class="skeleton skeleton-icon"></div><div class="skeleton skeleton-value"></div><div class="skeleton skeleton-label"></div></div>`
  ).join('')}</div>`;
}

export function skeletonChart(height = 280) {
  return `<div class="skeleton skeleton-chart" style="height:${height}px"></div>`;
}

export function progressBar(pct, color = 'var(--primary)', showLabel = false) {
  const width = Math.min(Math.max(pct, 0), 100);
  return `
    <div class="progress-wrap">
      <div class="progress-track" role="progressbar" aria-valuenow="${width}" aria-valuemin="0" aria-valuemax="100">
        <div class="progress-fill" style="width:${width}%;background:${color}"></div>
      </div>
      ${showLabel ? `<span class="progress-label" style="color:${color}">${width.toFixed(0)}%</span>` : ''}
    </div>`;
}

export function badge(text, variant = 'info') {
  return `<span class="badge badge-${variant}">${esc(text)}</span>`;
}

export function typeBadge(type) {
  return badge(type, type === 'INCOME' ? 'income' : 'expense');
}

export function insightIcon(type) {
  if (type === 'WARNING') return icon('warning', 'sm');
  if (type === 'ACHIEVEMENT') return icon('award', 'sm');
  return icon('info', 'sm');
}

export function budgetStatusBadge(status) {
  if (status === 'EXCEEDED') return badge('Exceeded', 'expense');
  if (status === 'WARNING') return badge('Warning', 'warning');
  return badge('On track', 'income');
}

export function insightColor(type) {
  if (type === 'WARNING') return 'var(--accent)';
  if (type === 'ACHIEVEMENT') return 'var(--accent-g)';
  return 'var(--accent-b)';
}

/* ── Financial Health panel ──────────────────────────────────────────────── */

const HEALTH_DIMENSION_MAX = {
  'Savings Rate': 300,
  'Diversification': 200,
  'Consistency': 200,
  'Income vs Expense': 200,
  'Tracking Habit': 100,
};

function dimensionColor(pct) {
  if (pct >= 0.7) return 'var(--accent-g)';
  if (pct >= 0.4) return 'var(--accent-y)';
  return 'var(--accent)';
}

export function healthPanelHtml(prefix) {
  return `
    <div class="card insight-card insight-card--health health-card fade-up">
      <div class="insight-card-header">
        <span class="insight-card-icon">${icon('shield')}</span>
        <div class="insight-card-heading">
          <h3>Financial Health</h3>
          <p class="insight-card-sub">Your score out of 1,000</p>
        </div>
      </div>
      <div class="health-panel">
        <div class="health-ring" id="${prefix}-health-ring">
          <div class="health-ring-inner">
            <span class="health-score" id="${prefix}-health-score">—</span>
            <span class="health-score-max">out of 1000</span>
            <span class="health-grade" id="${prefix}-health-grade">Grade —</span>
          </div>
        </div>
        <div class="health-details">
          <p id="${prefix}-health-summary" class="health-summary">Calculating your financial health…</p>
          <div id="${prefix}-health-breakdown" class="health-breakdown"></div>
        </div>
      </div>
    </div>`;
}

export function renderHealthData(prefix, health) {
  if (!health) return;
  const elScore = document.getElementById(`${prefix}-health-score`);
  const elRing = document.getElementById(`${prefix}-health-ring`);

  const isNew = health.grade === 'N/A';

  if (elScore) {
    elScore.textContent = isNew ? '—' : health.totalScore;
    elScore.style.color = isNew ? 'var(--text-muted)' : healthScoreColor(health.totalScore);
  }
  if (elRing) {
    const pct = isNew ? 0 : Math.min(health.totalScore / 10, 100);
    elRing.style.setProperty('--ring-pct', pct);
    elRing.style.setProperty('--ring-color', isNew ? 'var(--bg-lighter)' : healthScoreColor(health.totalScore));
  }
  const elGrade = document.getElementById(`${prefix}-health-grade`);
  if (elGrade) elGrade.textContent = isNew ? 'Unranked' : `Grade ${health.grade}`;
  const elSummary = document.getElementById(`${prefix}-health-summary`);
  if (elSummary) elSummary.textContent = health.summary;
  const elBreakdown = document.getElementById(`${prefix}-health-breakdown`);
  if (elBreakdown && health.breakdown) {
    elBreakdown.innerHTML = Object.entries(health.breakdown).map(([key, raw]) => {
      const value = Number(raw) || 0;
      const max = HEALTH_DIMENSION_MAX[key] || 100;
      const pct = max > 0 ? Math.min(Math.max(value / max, 0), 1) : 0;
      const color = dimensionColor(pct);
      return `
        <div class="health-metric">
          <div class="health-metric-head">
            <span>${esc(key)}</span>
            <strong>${esc(String(value))}<span> / ${max}</span></strong>
          </div>
          <div class="health-metric-track" role="progressbar" aria-valuenow="${value}" aria-valuemin="0" aria-valuemax="${max}" aria-label="${esc(key)}">
            <div class="health-metric-fill" style="width:${(pct * 100).toFixed(0)}%;background:${color}"></div>
          </div>
        </div>`;
    }).join('');
  }
}

/* ── AI Advisor panel ────────────────────────────────────────────────────── */

const AI_TYPE_CONFIG = {
  WARNING:     { label: 'Attention',       icon: 'warning',  color: 'var(--accent)',   bg: 'rgba(249, 115, 22, 0.09)' },
  ACHIEVEMENT: { label: 'Win',             icon: 'award',    color: 'var(--accent-g)', bg: 'rgba(34, 201, 147, 0.09)' },
  TIP:         { label: 'Suggestion',      icon: 'sparkles', color: 'var(--accent-b)', bg: 'rgba(56, 189, 248, 0.09)' },
  INFO:        { label: 'Insight',         icon: 'info',     color: 'var(--accent-b)', bg: 'rgba(56, 189, 248, 0.09)' },
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

function relativeTime(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const minutes = Math.round((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 1440) return `${Math.round(minutes / 60)} hr ago`;
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

function aiInsightHtml(ins) {
  const cfg = aiTypeConfig(ins.type);
  return `
    <article class="advisor-insight" data-type="${ins.type}" data-priority="${ins.priority}"
             style="--insight-color:${cfg.color};--insight-bg:${cfg.bg}">
      <div class="advisor-insight-head">
        <span class="advisor-badge">${icon(cfg.icon, 'xs')}${cfg.label}</span>
        ${ins.priority <= 2 ? '<span class="advisor-priority">High priority</span>' : ''}
      </div>
      <strong class="advisor-insight-title">${esc(ins.title)}</strong>
      <p class="advisor-insight-message">${esc(ins.message)}</p>
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

function wireAiFilters({ insights, filtersEl, toolbarEl, countEl, listEl }) {
  if (!filtersEl || !toolbarEl) return;
  const counts = {};
  insights.forEach(i => { counts[i.type] = (counts[i.type] || 0) + 1; });
  const present = ['WARNING', 'TIP', 'ACHIEVEMENT', 'INFO'].filter(t => counts[t]);

  const setCount = (n) => {
    if (countEl) countEl.textContent = `${n} insight${n === 1 ? '' : 's'}`;
  };

  if (present.length < 2) {
    toolbarEl.hidden = true;
    setCount(insights.length);
    return;
  }

  const labelFor = (t) => t === 'ACHIEVEMENT' ? 'Wins' : t === 'WARNING' ? 'Warnings' : t === 'TIP' ? 'Suggestions' : 'Insights';
  filtersEl.innerHTML = [
    `<button type="button" class="advisor-filter is-active" data-type="ALL">All <span>${insights.length}</span></button>`,
    ...present.map(t => `
      <button type="button" class="advisor-filter" data-type="${t}">
        <i style="background:${aiTypeConfig(t).color}"></i>${labelFor(t)} <span>${counts[t]}</span>
      </button>`),
  ].join('');

  toolbarEl.hidden = false;
  setCount(insights.length);

  filtersEl.querySelectorAll('.advisor-filter').forEach(btn => {
    btn.onclick = () => {
      filtersEl.querySelectorAll('.advisor-filter').forEach(b => b.classList.remove('is-active'));
      btn.classList.add('is-active');
      const type = btn.dataset.type;
      let visible = 0;
      listEl.querySelectorAll('.advisor-insight').forEach(card => {
        const show = type === 'ALL' || card.dataset.type === type;
        card.hidden = !show;
        if (show) visible += 1;
      });
      setCount(visible);
    };
  });
}

export function aiPanelHtml(prefix) {
  const skeleton = Array.from({ length: 4 }, () => `
    <div class="advisor-insight advisor-skeleton" aria-hidden="true">
      <div class="skeleton advisor-skeleton-line advisor-skeleton-line--short"></div>
      <div class="skeleton advisor-skeleton-line"></div>
      <div class="skeleton advisor-skeleton-line"></div>
    </div>`).join('');
  return `
    <div class="card advisor-card fade-up">
      <div class="advisor-header">
        <div class="insight-card-header">
          <span class="insight-card-icon">${icon('sparkles')}</span>
          <div class="insight-card-heading">
            <h3>AI Financial Advisor</h3>
            <p class="insight-card-sub" id="${prefix}-ai-meta">Analyzing your recent activity…</p>
          </div>
        </div>
        <button type="button" class="btn btn-ghost btn-sm advisor-refresh" id="${prefix}-ai-refresh">
          ${icon('refresh', 'xs')}<span>Refresh</span>
        </button>
      </div>
      <p id="${prefix}-ai-summary" class="ai-summary">Analyzing your transactions…</p>
      <div class="advisor-toolbar" id="${prefix}-ai-toolbar" hidden>
        <div class="advisor-filters" id="${prefix}-ai-filters" role="group" aria-label="Filter advice"></div>
        <span class="advisor-count" id="${prefix}-ai-count"></span>
      </div>
      <div id="${prefix}-ai-insights-list" class="advisor-grid" aria-live="polite" aria-busy="true">
        ${skeleton}
      </div>
      <div id="${prefix}-ai-goals" class="advisor-goals" hidden></div>
    </div>`;
}

/**
 * Renders AI insights into an advisor panel created by {@link aiPanelHtml}.
 * @param {string} prefix
 * @param {{summary?:string, insights?:Array, generatedAt?:string, error?:string}|null} ai
 * @param {Function} [onRefresh] clicked refresh handler
 */
export function renderAiData(prefix, ai, onRefresh) {
  const elSummary = document.getElementById(`${prefix}-ai-summary`);
  const elMeta = document.getElementById(`${prefix}-ai-meta`);
  const elList = document.getElementById(`${prefix}-ai-insights-list`);
  const elToolbar = document.getElementById(`${prefix}-ai-toolbar`);
  const elFilters = document.getElementById(`${prefix}-ai-filters`);
  const elCount = document.getElementById(`${prefix}-ai-count`);
  const elRefresh = document.getElementById(`${prefix}-ai-refresh`);

  if (elRefresh && typeof onRefresh === 'function') elRefresh.onclick = () => onRefresh();
  if (!elList) return;
  elList.setAttribute('aria-busy', 'false');

  if (!ai) {
    if (elSummary) elSummary.textContent = 'Advice is unavailable right now.';
    if (elToolbar) elToolbar.hidden = true;
    elList.innerHTML = aiNoticeHtml('info', 'No advice available', 'Try refreshing in a moment.');
    return;
  }

  if (ai.error) {
    if (elSummary) elSummary.textContent = 'The advisor could not load your insights.';
    if (elMeta) elMeta.textContent = 'Something went wrong';
    if (elToolbar) elToolbar.hidden = true;
    elList.innerHTML = aiNoticeHtml('alert-triangle', 'Could not load advice', ai.error, 'advisor-notice--error');
    return;
  }

  const insights = (ai.insights || [])
    .map(normalizeInsight)
    .filter(Boolean)
    .sort((a, b) => a.priority - b.priority);

  if (elSummary) elSummary.textContent = ai.summary || 'Here is what your recent activity suggests.';
  if (elMeta) {
    const when = ai.generatedAt ? relativeTime(ai.generatedAt) : '';
    elMeta.textContent = when ? `Updated ${when}` : 'Personalized from your recent activity';
  }

  if (!insights.length) {
    if (elToolbar) elToolbar.hidden = true;
    elList.innerHTML = aiNoticeHtml('check-circle', 'All clear',
      'No concerns found in your recent activity. Add more transactions for deeper advice.');
    return;
  }

  elList.innerHTML = insights.map(aiInsightHtml).join('');
  wireAiFilters({ insights, filtersEl: elFilters, toolbarEl: elToolbar, countEl: elCount, listEl: elList });
}

export function openModal(title, bodyHtml, { onSubmit, submitLabel = 'Save', size = '' } = {}) {
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

  const close = () => overlay.remove();
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

  // Initialize Flatpickr for industrial grade date pickers
  if (window.flatpickr) {
    flatpickr(overlay.querySelectorAll('input[type="date"]'), {
      dateFormat: "Y-m-d",
      allowInput: true,
      altInput: true,
      altFormat: "F j, Y",
      disableMobile: true
    });
  }

  return { overlay, close };
}

export function confirmModal(title, message, confirmText = 'Confirm', cancelText = 'Cancel') {
  return new Promise((resolve) => {
    const { overlay, close } = openModal(title, `
      <p style="margin-bottom: 24px; color: var(--text-dim);">${esc(message)}</p>
      <div class="modal-actions">
        <button type="button" class="btn btn-secondary" id="confirm-cancel-btn">${esc(cancelText)}</button>
        <button type="button" class="btn" style="background: var(--accent-g); color: #000;" id="confirm-ok-btn">${esc(confirmText)}</button>
      </div>
    `);

    overlay.querySelector('#confirm-cancel-btn').onclick = () => {
      close();
      resolve(false);
    };

    overlay.querySelector('#confirm-ok-btn').onclick = () => {
      close();
      resolve(true);
    };

    // Override the default close behavior to resolve false
    const originalClose = overlay.querySelector('.modal-close').onclick;
    overlay.querySelector('.modal-close').onclick = (e) => {
      originalClose(e);
      resolve(false);
    };
    overlay.onclick = (e) => {
      if (e.target === overlay) {
        close();
        resolve(false);
      }
    };
  });
}

export function modalActions(cancelLabel = 'Cancel', submitLabel = 'Save') {
  return `
    <div class="modal-actions">
      <button type="button" class="btn btn-secondary" data-modal-cancel>${esc(cancelLabel)}</button>
      <button type="submit" class="btn btn-primary">${esc(submitLabel)}</button>
    </div>`;
}

export function dataField(label, value) {
  return `
    <div class="data-field">
      <span class="data-field-label">${esc(label)}</span>
      <span class="data-field-value">${esc(value ?? '—')}</span>
    </div>`;
}

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

export function setupCategorySearch(inputId, selectId) {
  const input = document.getElementById(inputId);
  const selectElement = document.getElementById(selectId);
  if (!selectElement) return;

  // Hide our manual search input since Tom Select has its own
  if (input) {
    input.style.display = 'none';
  }

  // If Tom Select is loaded, initialize it
  if (window.TomSelect) {
    // Check if it's already initialized to avoid errors on re-renders
    if (selectElement.tomselect) {
      selectElement.tomselect.destroy();
    }
    
    new TomSelect(selectElement, {
      create: false,
      sortField: {
        field: "text",
        direction: "asc"
      },
      placeholder: 'Search category...',
      maxOptions: 100
    });
  }
}

export function openReceiptLightbox(url) {
  const isPdf = url.toLowerCase().endsWith('.pdf');
  
  if (isPdf) {
    // Open PDFs in a new tab to avoid iframe security restrictions
    window.open(url, '_blank');
  } else {
    // Show images in the lightbox modal
    const content = `<div style="text-align:center; padding: 10px;"><img src="${url}" style="max-width:100%; max-height: 80vh; object-fit: contain; border-radius: 8px;"></div>`;
    openModal('Receipt', content, { size: 'lg' });
  }
}
window.openReceiptLightbox = openReceiptLightbox;
