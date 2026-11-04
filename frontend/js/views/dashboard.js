import { api, Auth, toast } from '../utils/api.js?v=2026110417';
import { icon } from '../utils/icons.js?v=2026110417';
import {
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData,
  formatMoney, formatCategory, formatDate, esc
} from '../utils/ui.js?v=2026110417';

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
    <div class="period-header fade-up">
      <h1 id="d-period-label">${esc(periodHeading(currentPeriod))}</h1>
      <select class="period-select" id="d-period" aria-label="Reporting period">
        ${PERIODS.map(p => `<option value="${p.id}" ${p.id === currentPeriod ? 'selected' : ''}>${p.label}</option>`).join('')}
      </select>
    </div>
    <div class="figures fade-up" id="d-kpis" aria-live="polite">
      ${figureSkeleton('Earned')}${figureSkeleton('Spent')}${figureSkeleton('Net')}${figureSkeleton('Saved')}
    </div>
    <div class="dash-grid fade-up">
      <section>
        <div class="ledger-head">
          <h2>Recent transactions</h2>
          <button type="button" class="btn btn-ghost btn-sm" onclick="navigateTo('transactions')">View all</button>
        </div>
        <div id="d-recent" aria-live="polite">
          <div class="spinner-center"><span class="spinner"></span></div>
        </div>
      </section>
      <aside>
        <div id="d-alerts"></div>
        ${healthPanelHtml('d')}
        ${aiPanelHtml('d')}
      </aside>
    </div>`;

  document.getElementById('d-period').onchange = (e) => {
    currentPeriod = e.target.value;
    const label = document.getElementById('d-period-label');
    if (label) label.textContent = periodHeading(currentPeriod);
    loadSummary();
  };

  const loadSummary = () => {
    const range = periodRange(currentPeriod);
    api.get('/upsert/summary', { userId, ...range })
      .then(renderFigures)
      .catch(() => renderFigures(null));
    api.get('/upsert/entries', { userId, page: 0, size: 6, ...range })
      .then(renderRecent)
      .catch(() => renderRecent(null));
  };

  loadSummary();

  // AI insights call the LLM and can take seconds. Start that request now but
  // never await it with the rest of the page.
  api.get('/analytics/ai-insights', { userId })
    .then((ai) => {
      if (document.getElementById('d-ai-insights-list')) renderAiData('d', ai, refreshAdvisor);
    })
    .catch((e) => {
      if (document.getElementById('d-ai-insights-list')) {
        renderAiData('d', { error: e.message }, refreshAdvisor);
      }
    });

  const aiRefresh = document.getElementById('d-ai-refresh');
  if (aiRefresh) aiRefresh.onclick = () => refreshAdvisor();

  try {
    const [health, budgets, goals] = await Promise.all([
      api.get('/analytics/health-score', { userId }).catch(() => null),
      api.get('/upsert/budgets', { userId }).catch(() => []),
      api.get('/upsert/goals', { userId }).catch(() => [])
    ]);

    const alertsContainer = document.getElementById('d-alerts');
    if (!alertsContainer) return; // User navigated away

    const alerts = [];
    for (const b of budgets) {
      if (b.utilizationPercentage >= 100) {
        alerts.push(attentionItem('danger',
          `${formatCategory(b.expenseCategory)} budget is over`,
          `Spent ${formatMoney(b.spentAmount, b.currency)} of ${formatMoney(b.budgetAmount, b.currency)}.`));
      } else if (b.utilizationPercentage >= 80) {
        alerts.push(attentionItem('warning',
          `${formatCategory(b.expenseCategory)} at ${b.utilizationPercentage.toFixed(0)}%`,
          `${formatMoney(b.budgetAmount - b.spentAmount, b.currency)} left this period.`));
      }
    }
    alertsContainer.innerHTML = alerts.join('');

    const goalsContainer = document.getElementById('d-ai-goals');
    const activeGoals = goals.filter(g => !g.completed);
    if (goalsContainer && activeGoals.length > 0) {
      const parts = [];
      for (const g of activeGoals) {
        try {
          const forecast = await api.get(`/analytics/goals/${g.id}/forecast`, { userId: Auth.getUserId() });
          parts.push(`
            <div class="advisor-goal">
              <div class="advisor-goal-body">
                <div class="advisor-goal-name">${esc(g.name)}</div>
                <div class="advisor-goal-msg">${esc(forecast.message)}</div>
              </div>
            </div>`);
        } catch (e) { /* a missing forecast must not block the page */ }
      }
      if (parts.length) {
        goalsContainer.innerHTML = `
          <div class="advisor-goals-head">Goals</div>
          <div class="advisor-goals-row">${parts.join('')}</div>`;
        goalsContainer.hidden = false;
      }
    }

    renderHealthData('d', health);
  } catch (err) {
    toast('Failed to load dashboard: ' + err.message, 'error');
  }
}

function periodHeading(period) {
  const now = new Date();
  if (period === 'month') {
    return now.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  }
  if (period === 'last') {
    const d = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    return d.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  }
  if (period === 'quarter') return 'Last 3 months';
  return 'All time';
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

function figureSkeleton(label) {
  return `<div class="figure"><div class="figure-label">${label}</div><div class="figure-value" style="opacity:.35">…</div></div>`;
}

function renderFigures(summary) {
  const wrap = document.getElementById('d-kpis');
  if (!wrap) return; // User navigated away
  const currency = Auth.getDefaultCurrency();
  const income = Number(summary?.totalIncome) || 0;
  const expense = Number(summary?.totalExpense) || 0;
  const net = Number(summary?.netBalance) || 0;
  const savedRate = income > 0 ? Math.max(0, (net / income) * 100) : 0;

  wrap.innerHTML = `
    <div class="figure">
      <div class="figure-label">Earned</div>
      <div class="figure-value ${income > 0 ? 'is-pos' : ''}">${formatMoney(income, currency)}</div>
    </div>
    <div class="figure">
      <div class="figure-label">Spent</div>
      <div class="figure-value">${formatMoney(expense, currency)}</div>
    </div>
    <div class="figure">
      <div class="figure-label">Net</div>
      <div class="figure-value ${net < 0 ? 'is-neg' : 'is-pos'}">${formatMoney(net, currency)}</div>
    </div>
    <div class="figure">
      <div class="figure-label">Saved</div>
      <div class="figure-value">${income > 0 ? `${savedRate.toFixed(0)}% of income` : '—'}</div>
    </div>`;
}

function dayKey(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'Unknown';
  return isoDate(d);
}

function dayLabel(key) {
  const today = isoDate(new Date());
  const yesterday = isoDate(new Date(Date.now() - 86400000));
  if (key === today) return 'Today';
  if (key === yesterday) return 'Yesterday';
  const d = new Date(`${key}T00:00:00`);
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

function renderRecent(result) {
  const wrap = document.getElementById('d-recent');
  if (!wrap) return; // User navigated away
  const items = result?.content || [];
  if (!items.length) {
    wrap.innerHTML = `<p style="color:var(--text-muted);font-size:0.88rem;padding:18px 0;">No transactions in this period. Add one to get started.</p>`;
    return;
  }
  const currency = Auth.getDefaultCurrency();

  // Group by calendar day so the date is stated once, not on every row.
  const groups = new Map();
  for (const t of items) {
    const key = dayKey(t.createdAt);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(t);
  }

  wrap.innerHTML = [...groups.entries()].map(([key, rows]) => {
    const total = rows.reduce((sum, t) => sum + (t.type === 'INCOME' ? Number(t.amount) : -Number(t.amount)), 0);
    const rowsHtml = rows.map(t => {
      const isIncome = t.type === 'INCOME';
      const amount = formatMoney(isIncome ? Number(t.amount) : -Number(t.amount), t.currency || currency);
      const category = formatCategory(t.expenseCategory || t.incomeCategory || t.category || '');
      return `
        <div class="ledger-row" role="button" tabindex="0" onclick="navigateTo('transactions')">
          <div class="ledger-name">${esc(t.name || 'Transaction')}</div>
          <div class="ledger-cat">${esc(category)}</div>
          <div class="ledger-amount ${isIncome ? 'is-pos' : 'is-neg'}">${amount}</div>
        </div>`;
    }).join('');
    return `
      <div class="ledger-day"><span>${esc(dayLabel(key))}</span><span>${formatMoney(total, currency)}</span></div>
      ${rowsHtml}`;
  }).join('');
}

function attentionItem(tone, title, body) {
  return `<div class="attention-item is-${tone}"><div><strong>${esc(title)}</strong><br>${esc(body)}</div></div>`;
}

async function refreshAdvisor() {
  const btn = document.getElementById('d-ai-refresh');
  if (btn) btn.disabled = true;
  try {
    const ai = await api.get('/analytics/ai-insights', { userId: Auth.getUserId(), refresh: true });
    renderAiData('d', ai, refreshAdvisor);
    toast('Advice refreshed', 'success');
  } catch (err) {
    renderAiData('d', { error: err.message }, refreshAdvisor);
    toast('Could not refresh advice: ' + err.message, 'error');
  } finally {
    const current = document.getElementById('d-ai-refresh');
    if (current) current.disabled = false;
  }
}
