import { api, Auth, toast } from '../utils/api.js';
import { icon, categoryIcon } from '../utils/icons.js';
import {
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData,
  formatMoney, formatCategory, formatDayLabel, esc, segmentedControl, wireSegmented,
  amountHtml, progressBar, skeletonRows, alertBanner,
} from '../utils/ui.js';

const PERIODS = [
  { id: 'month', label: 'This month' },
  { id: 'last', label: 'Last month' },
  { id: 'quarter', label: 'Last 3 months' },
  { id: 'all', label: 'All time' },
];

let currentPeriod = 'month';

export async function renderDashboard(container) {
  const userId = Auth.getUserId();

  container.innerHTML = `
    <div class="period-header">
      <h1 id="d-period-label">${esc(periodHeading(currentPeriod))}</h1>
      ${segmentedControl('dashboard-period', PERIODS, currentPeriod, 'Reporting period')}
    </div>
    <div id="d-kpis" aria-live="polite">
      <div class="dash-hero">
        <div class="dash-hero-label">Net</div>
        <div class="dash-hero-top">
          <span class="hero-number" id="d-net">—</span>
          <span class="dash-delta" id="d-delta"></span>
        </div>
        <div class="dash-sub">
          <span class="dash-sub-item">Earned <strong id="d-earned">—</strong></span>
          <span class="dash-sub-item">Spent <strong id="d-spent">—</strong></span>
        </div>
        <div class="dash-saved" id="d-saved-wrap" hidden>
          <div class="dash-saved-label"><span id="d-saved-label"></span><span id="d-saved-pct"></span></div>
          ${progressBar(0, 'var(--pos)')}
        </div>
      </div>
    </div>

    <div class="dash-grid">
      <section aria-labelledby="d-recent-title">
        <div id="d-alerts"></div>
        <div class="ledger-head">
          <h2 id="d-recent-title" class="section-title">Recent transactions</h2>
          <button type="button" class="btn btn-ghost btn-sm" data-nav="transactions">View all</button>
        </div>
        <div id="d-recent" aria-live="polite">
          ${skeletonRows(5)}
        </div>
      </section>
      <aside>
        ${aiPanelHtml('d')}
        ${healthPanelHtml('d')}
        <section class="health-panel goal-summary" id="d-goals-summary" aria-labelledby="d-goals-title" hidden>
          <div class="health-head">
            <h3 class="section-title" id="d-goals-title">Goals</h3>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="goals">View all</button>
          </div>
          <div id="d-goals-rows"></div>
        </section>
      </aside>
    </div>`;

  wireSegmented(container, (period) => {
    currentPeriod = period;
    const label = document.getElementById('d-period-label');
    if (label) label.textContent = periodHeading(currentPeriod);
    loadSummary();
  });

  container.querySelectorAll('[data-nav]').forEach(btn => {
    btn.onclick = () => window.navigateTo(btn.dataset.nav);
  });

  const loadSummary = () => {
    const range = periodRange(currentPeriod);
    const prev = previousRange(currentPeriod);

    api.get('/upsert/summary', { userId, ...range })
      .then(summary => {
        if (!document.getElementById('d-net')) return;
        if (prev) {
          api.get('/upsert/summary', { userId, ...prev })
            .then(previous => renderFigures(summary, previous))
            .catch(() => renderFigures(summary, null));
        } else {
          renderFigures(summary, null);
        }
      })
      .catch(() => renderFigures(null, null));

    api.get('/upsert/entries', { userId, page: 0, size: 6, ...range })
      .then(renderRecent)
      .catch(() => renderRecent(null));
  };

  loadSummary();

  // AI insights can take seconds; never block the rest of the page on it.
  api.get('/analytics/ai-insights', { userId })
    .then((ai) => {
      if (document.getElementById('d-ai-insights-list')) renderAiData('d', ai, refreshAdvisor);
    })
    .catch((e) => {
      if (document.getElementById('d-ai-insights-list')) renderAiData('d', { error: e.message }, refreshAdvisor);
    });

  try {
    const [health, budgets, goals] = await Promise.all([
      api.get('/analytics/health-score', { userId }).catch(() => null),
      api.get('/upsert/budgets', { userId }).catch(() => []),
      api.get('/upsert/goals', { userId }).catch(() => []),
    ]);

    if (!document.getElementById('d-health-score')) return; // navigated away

    renderBudgetAlerts(budgets);
    const capped = await renderGoalsSummary(goals, userId);
    renderHealthData('d', health, { capLabel: capped });
  } catch (err) {
    toast('Failed to load dashboard: ' + err.message, 'error');
  }
}

function periodHeading(period) {
  const now = new Date();
  if (period === 'month') return now.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  if (period === 'last') {
    const d = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    return d.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  }
  if (period === 'quarter') return 'Last 3 months';
  return 'All time';
}

/** The period the delta is measured against, e.g. "October". */
function previousPeriodLabel(period) {
  const now = new Date();
  if (period === 'month') return new Date(now.getFullYear(), now.getMonth() - 1, 1)
    .toLocaleDateString('en-IN', { month: 'long' });
  if (period === 'last') return new Date(now.getFullYear(), now.getMonth() - 2, 1)
    .toLocaleDateString('en-IN', { month: 'long' });
  if (period === 'quarter') return 'the previous 3 months';
  return 'the previous period';
}

function isoDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function periodRange(period) {
  const now = new Date();
  if (period === 'all') return {};
  if (period === 'month') {
    return {
      startDate: isoDate(new Date(now.getFullYear(), now.getMonth(), 1)),
      endDate: isoDate(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
    };
  }
  if (period === 'last') {
    return {
      startDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
      endDate: isoDate(new Date(now.getFullYear(), now.getMonth(), 0)),
    };
  }
  return {
    startDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 2, 1)),
    endDate: isoDate(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
  };
}

function previousRange(period) {
  const now = new Date();
  if (period === 'all') return null;
  if (period === 'month') {
    return {
      startDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 1, 1)),
      endDate: isoDate(new Date(now.getFullYear(), now.getMonth(), 0)),
    };
  }
  if (period === 'last') {
    return {
      startDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 2, 1)),
      endDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 1, 0)),
    };
  }
  return {
    startDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 5, 1)),
    endDate: isoDate(new Date(now.getFullYear(), now.getMonth() - 2, 0)),
  };
}

function renderFigures(summary, previous) {
  const wrap = document.getElementById('d-kpis');
  if (!wrap) return;

  const currency = Auth.getDefaultCurrency();
  const income = Number(summary?.totalIncome) || 0;
  const expense = Number(summary?.totalExpense) || 0;
  const net = Number(summary?.netBalance) || 0;
  const savedRate = income > 0 ? Math.max(0, (net / income) * 100) : 0;

  const earnedEl = document.getElementById('d-earned');
  const spentEl = document.getElementById('d-spent');
  const netValue = document.getElementById('d-net');
  if (earnedEl) earnedEl.textContent = formatMoney(income, currency);
  if (spentEl) spentEl.textContent = formatMoney(expense, currency);
  if (netValue) {
    netValue.textContent = formatMoney(net, currency);
    netValue.className = `hero-number ${net < 0 ? 'is-neg' : ''}`;
  }

  // Delta against the previous period of the same length.
  const delta = document.getElementById('d-delta');
  if (delta) {
    if (!previous) {
      delta.textContent = '';
    } else {
      const prevNet = Number(previous.netBalance) || 0;
      const diff = net - prevNet;
      const label = previousPeriodLabel(currentPeriod);
      if (Math.abs(diff) < 0.005) {
        delta.textContent = `No change vs ${label}`;
        delta.className = 'dash-delta';
      } else {
        const pct = prevNet !== 0 ? Math.abs(diff / Math.abs(prevNet)) * 100 : null;
        delta.innerHTML = `${icon(diff > 0 ? 'arrow-up' : 'arrow-down', 'xs')}
          ${esc(formatMoney(Math.abs(diff), currency))} ${diff > 0 ? 'up' : 'down'} vs ${esc(label)}
          ${pct != null && pct < 999 ? ` · ${pct.toFixed(0)}%` : ''}`;
        delta.className = `dash-delta ${diff > 0 ? 'is-pos' : 'is-neg'}`;
      }
    }
  }

  const savedWrap = document.getElementById('d-saved-wrap');
  if (savedWrap) {
    savedWrap.hidden = !(income > 0);
    if (income > 0) {
      const label = document.getElementById('d-saved-label');
      const pct = document.getElementById('d-saved-pct');
      if (label) label.textContent = `Saved ${savedRate.toFixed(0)}% of income`;
      if (pct) pct.textContent = formatMoney(Math.max(net, 0), currency);
      const fill = savedWrap.querySelector('.progress-fill');
      const track = savedWrap.querySelector('.progress-track');
      if (fill) fill.style.background = savedRate >= 20 ? 'var(--pos)' : 'var(--warn)';
      if (track) {
        track.setAttribute('aria-valuenow', savedRate.toFixed(0));
        if (fill) fill.style.width = `${Math.min(savedRate, 100)}%`;
      }
    }
  }
}

function renderRecent(result) {
  const wrap = document.getElementById('d-recent');
  if (!wrap) return;
  const items = result?.content || [];
  const currency = Auth.getDefaultCurrency();

  if (!items.length) {
    wrap.innerHTML = `<p class="meta" style="padding:20px 0">No transactions in this period. Add one to get started.</p>`;
    return;
  }

  const groups = new Map();
  for (const t of items) {
    const key = (t.createdAt || '').slice(0, 10);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(t);
  }

  wrap.innerHTML = [...groups.entries()].map(([key, rows]) => {
    const total = rows.reduce((sum, t) => sum + (t.type === 'INCOME' ? Number(t.amount) : -Number(t.amount)), 0);
    const rowsHtml = rows.map(t => {
      const isIncome = t.type === 'INCOME';
      const cat = t.expenseCategory || t.incomeCategory || t.category || '';
      const amount = formatMoney(isIncome ? Number(t.amount) : -Number(t.amount), t.currency || currency);
      const tags = [
        t.recurring ? `<span class="txn-tag">${icon('repeat', 'xs')} ${esc(titleCase(t.recurringPeriod || 'Recurring'))}</span>` : '',
      ].join('');
      const receipt = t.receiptUrl
        ? `<button type="button" class="attach-btn" data-receipt="${esc(t.receiptUrl)}" aria-label="Receipt attached" title="Receipt attached">${icon('paperclip', 'sm')}</button>`
        : '';
      return `
        <div class="ledger-row" role="button" tabindex="0" data-txn="${esc(String(t.id || ''))}">
          <span class="dash-recent-icon">${icon(categoryIcon(cat), 'sm')}</span>
          <div class="ledger-name">
            ${esc(t.name || 'Transaction')}
            <div class="txn-sub">${esc(formatCategory(cat))}${tags}</div>
          </div>
          ${receipt}
          <span class="ledger-amount ${isIncome ? 'is-pos' : ''}">${amount}</span>
        </div>`;
    }).join('');

    return `
      <div class="ledger-day"><span>${esc(formatDayLabel(key))}</span><span class="day-total">${formatMoney(total, currency)}</span></div>
      ${rowsHtml}`;
  }).join('');

  wrap.querySelectorAll('.ledger-row').forEach(row => {
    row.onclick = () => window.navigateTo('transactions');
    row.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); window.navigateTo('transactions'); }
    };
  });
  wrap.querySelectorAll('[data-receipt]').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      window.openReceiptLightbox(btn.dataset.receipt);
    };
  });
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

function renderBudgetAlerts(budgets) {
  const container = document.getElementById('d-alerts');
  if (!container) return;
  const alerts = [];
  for (const b of budgets || []) {
    if (b.utilizationPercentage >= 100) {
      alerts.push(alertBanner(
        `${esc(formatCategory(b.expenseCategory))} is over budget: ${formatMoney(b.spentAmount, b.currency)} of ${formatMoney(b.budgetAmount, b.currency)}.`,
        'error'));
    } else if (b.utilizationPercentage >= 80) {
      alerts.push(alertBanner(
        `${esc(formatCategory(b.expenseCategory))} is at ${b.utilizationPercentage.toFixed(0)}% of its budget. ${formatMoney(b.budgetAmount - b.spentAmount, b.currency)} left.`,
        'warning'));
    }
  }
  container.innerHTML = alerts.slice(0, 2).join('');
}

/**
 * Goal rows with a mini progress bar and a status chip. Returns the label of
 * the first goal that is behind, which the health panel uses to withhold an
 * inconsistent top grade.
 */
async function renderGoalsSummary(goals, userId) {
  const active = (goals || []).filter(g => !g.completed);
  if (!active.length) return '';

  const forecasts = await Promise.allSettled(
    active.map(g => g.deadline
      ? api.get(`/analytics/goals/${g.id}/forecast`, { userId }).catch(() => null)
      : Promise.resolve(null))
  );

  let capped = '';
  const rows = active.map((g, i) => {
    const pct = Math.min(Number(g.progressPercentage) || 0, 100);
    const months = monthsUntil(g.deadline);
    const required = months ? Math.max(0, (Number(g.targetAmount) - Number(g.savedAmount)) / months) : null;
    const forecast = forecasts[i]?.status === 'fulfilled' ? forecasts[i].value : null;
    const behind = required != null && forecast && Number(forecast.monthlyVelocity || 0) < required;
    if (behind && !capped) capped = `Goal behind: ${g.name}`;
    return `
      <div class="goal-summary-row">
        <span class="goal-summary-name">${esc(g.name)}</span>
        <span class="goal-summary-bar">${progressBar(pct, behind ? 'var(--warn)' : 'var(--pos)')}</span>
        ${behind ? '<span class="chip chip--warn">Behind</span>' : '<span class="chip chip--pos">On track</span>'}
      </div>`;
  }).join('');

  const wrap = document.getElementById('d-goals-summary');
  const rowsEl = document.getElementById('d-goals-rows');
  if (wrap && rowsEl) {
    rowsEl.innerHTML = rows;
    wrap.hidden = false;
  }
  return capped;
}

function monthsUntil(deadline) {
  const end = new Date(deadline);
  if (Number.isNaN(end.getTime())) return null;
  const now = new Date();
  const months = (end.getFullYear() - now.getFullYear()) * 12 + (end.getMonth() - now.getMonth());
  return Math.max(1, months);
}

async function refreshAdvisor() {
  const btn = document.getElementById('d-ai-refresh');
  if (btn) btn.disabled = true;
  try {
    const ai = await api.get('/analytics/ai-insights', { userId: Auth.getUserId(), refresh: true });
    renderAiData('d', ai, refreshAdvisor);
    toast('Insights refreshed', 'success');
  } catch (err) {
    renderAiData('d', { error: err.message }, refreshAdvisor);
    toast('Could not refresh insights: ' + err.message, 'error');
  } finally {
    const current = document.getElementById('d-ai-refresh');
    if (current) current.disabled = false;
  }
}
