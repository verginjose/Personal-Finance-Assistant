import { api, Auth, toast } from '../utils/api.js?v=2026110309';
import { icon } from '../utils/icons.js?v=2026110309';
import {
  pageHeader,
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData,
  formatCurrency, formatCategory, formatDate, alertBanner, esc
} from '../utils/ui.js?v=2026110309';

export async function renderDashboard(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Dashboard', 'Your financial overview at a glance', '<button class="btn btn-primary btn-sm" onclick="openAddTransaction()">+ Add Transaction</button>')}
    <div id="d-alerts" style="display:flex;flex-direction:column;gap:10px;margin-bottom:20px;"></div>
    <div class="card-grid card-grid-4 fade-up" id="d-kpis" style="margin-bottom:20px" aria-live="polite">
      ${kpiSkeleton()}${kpiSkeleton()}${kpiSkeleton()}${kpiSkeleton()}
    </div>
    <div class="dashboard-top fade-up">
      ${healthPanelHtml('d')}
      ${aiPanelHtml('d')}
    </div>
    <div class="card fade-up" id="d-recent-card" style="margin-top:20px">
      <div class="card-header" style="display:flex;align-items:center;justify-content:space-between;gap:12px;">
        <h3>Recent activity</h3>
        <button type="button" class="btn btn-ghost btn-sm" onclick="navigateTo('transactions')">View all</button>
      </div>
      <div id="d-recent" aria-live="polite">
        <div class="spinner-center"><span class="spinner"></span></div>
      </div>
    </div>`;

  // Totals and recent rows are cheap and should paint immediately, not wait
  // behind the (slow) AI advisor or the health-score aggregation.
  api.get('/upsert/summary', { userId })
    .then(renderKpis)
    .catch(() => renderKpis(null));
  api.get('/upsert/entries', { userId, page: 0, size: 5 })
    .then(renderRecent)
    .catch(() => renderRecent(null));

  // AI insights call the LLM and can take seconds. Start that request now but
  // never await it with the rest of the page: the advisor panel already shows
  // its skeleton, and KPIs/charts must paint as soon as their own data lands.
  api.get('/analytics/ai-insights', { userId })
    .then((ai) => {
      if (document.getElementById('d-ai-insights-list')) renderAiData('d', ai, refreshAdvisor);
    })
    .catch((e) => {
      if (document.getElementById('d-ai-insights-list')) {
        renderAiData('d', { error: e.message }, refreshAdvisor);
      }
    });

  // Wire refresh immediately so it works while the first request is in flight.
  const aiRefresh = document.getElementById('d-ai-refresh');
  if (aiRefresh) aiRefresh.onclick = () => refreshAdvisor();

  try {
    const [health, budgets, goals] = await Promise.all([
      api.get('/analytics/health-score', { userId }).catch(() => null),
      api.get('/upsert/budgets', { userId }).catch(() => []),
      api.get('/upsert/goals', { userId }).catch(() => [])
    ]);

    // Budget Alerts
    const alertsContainer = document.getElementById('d-alerts');
    if (!alertsContainer) return; // User navigated away
    const alertsHtml = [];
    for (const b of budgets) {
      if (b.utilizationPercentage >= 100) {
        alertsHtml.push(alertBanner(`<strong>Budget Exceeded:</strong> You have spent ${b.utilizationPercentage.toFixed(1)}% of your ${b.expenseCategory.replace(/_/g,' ')} budget!`, 'error'));
      } else if (b.utilizationPercentage >= 90) {
        alertsHtml.push(alertBanner(`<strong>Critical Warning:</strong> You have spent ${b.utilizationPercentage.toFixed(1)}% of your ${b.expenseCategory.replace(/_/g,' ')} budget.`, 'error', 'alert-triangle'));
      } else if (b.utilizationPercentage >= 80) {
        alertsHtml.push(alertBanner(`<strong>Budget Warning:</strong> Approaching ${b.expenseCategory.replace(/_/g,' ')} limit (${b.utilizationPercentage.toFixed(1)}%).`, 'warning'));
      }
    }
    if (alertsHtml.length > 0) {
      alertsContainer.innerHTML = alertsHtml.join('');
    }

    // Income, expense and transaction totals now live in the KPI row above;
    // Analytics keeps the breakdowns and date-range filtering.

    // Goal forecasts are embedded in the AI advisor card, not a separate row.
    const goalsContainer = document.getElementById('d-ai-goals');
    const activeGoals = goals.filter(g => !g.completed);
    if (goalsContainer && activeGoals.length > 0) {
      const parts = [];
      for (const g of activeGoals) {
        try {
          const forecast = await api.get(`/analytics/goals/${g.id}/forecast`, { userId: Auth.getUserId() });
          const velocity = forecast.monthlyVelocity > 0
            ? `<span class="advisor-goal-velocity">${icon('trending-up', 'xs')} ${formatCurrency(forecast.monthlyVelocity, g.currency)}/mo</span>`
            : '';
          parts.push(`
            <div class="advisor-goal">
              <div class="advisor-goal-icon">${icon('target', 'sm')}</div>
              <div class="advisor-goal-body">
                <div class="advisor-goal-name">${esc(g.name)}</div>
                <div class="advisor-goal-msg">${esc(forecast.message)}</div>
                ${velocity}
              </div>
            </div>`);
        } catch (e) { console.error('Failed to forecast goal', g.id); }
      }
      if (parts.length) {
        goalsContainer.innerHTML = `
          <div class="advisor-goals-head">${icon('target', 'xs')} Goal forecasts</div>
          <div class="advisor-goals-row">${parts.join('')}</div>`;
        goalsContainer.hidden = false;
      }
    }

    renderHealthData('d', health);
  } catch (err) {
    toast('Failed to load dashboard: ' + err.message, 'error');
  }
}

function kpiSkeleton() {
  return `<div class="card stat-card"><div class="stat-label">&nbsp;</div><div class="stat-value" style="opacity:.35">…</div></div>`;
}

function renderKpis(summary) {
  const wrap = document.getElementById('d-kpis');
  if (!wrap) return; // User navigated away
  const currency = Auth.getDefaultCurrency();
  const income = Number(summary?.totalIncome) || 0;
  const expense = Number(summary?.totalExpense) || 0;
  const net = Number(summary?.netBalance) || 0;
  const count = Number(summary?.totalCount) || 0;

  wrap.innerHTML = `
    <div class="card stat-card balance">
      <div class="stat-card-top"><div class="stat-label">Total balance</div></div>
      <div class="stat-value ${net < 0 ? 'is-negative' : ''}">${formatCurrency(net, currency)}</div>
      <div class="stat-hint">All time</div>
    </div>
    <div class="card stat-card income">
      <div class="stat-card-top"><div class="stat-label">Income</div></div>
      <div class="stat-value">${formatCurrency(income, currency)}</div>
      <div class="stat-hint">All time</div>
    </div>
    <div class="card stat-card expense">
      <div class="stat-card-top"><div class="stat-label">Expenses</div></div>
      <div class="stat-value">${formatCurrency(expense, currency)}</div>
      <div class="stat-hint">All time</div>
    </div>
    <div class="card stat-card count">
      <div class="stat-card-top"><div class="stat-label">Transactions</div><div class="stat-icon">${icon('list')}</div></div>
      <div class="stat-value">${count}</div>
      <div class="stat-hint">All time</div>
    </div>`;
}

function renderRecent(result) {
  const wrap = document.getElementById('d-recent');
  if (!wrap) return; // User navigated away
  const items = result?.content || [];
  if (!items.length) {
    wrap.innerHTML = `<p style="color:var(--text-dim);font-size:0.9rem;">No transactions yet. Add one to get started.</p>`;
    return;
  }
  const currency = Auth.getDefaultCurrency();
  wrap.innerHTML = items.map(t => {
    const isIncome = t.type === 'INCOME';
    const amount = formatCurrency(t.amount, t.currency || currency);
    const meta = [formatCategory(t.expenseCategory || t.incomeCategory || ''), formatDate(t.createdAt)]
      .filter(Boolean).join(' · ');
    return `
      <div class="dash-recent-row" role="button" tabindex="0" onclick="navigateTo('transactions')">
        <div class="dash-recent-icon ${isIncome ? 'is-income' : 'is-expense'}">${icon(isIncome ? 'trending-up' : 'trending-down', 'sm')}</div>
        <div class="dash-recent-body">
          <div class="dash-recent-name">${esc(t.name || 'Transaction')}</div>
          <div class="dash-recent-meta">${esc(meta)}</div>
        </div>
        <div class="dash-recent-amount ${isIncome ? 'is-income' : 'is-expense'}">${isIncome ? '+' : '−'}${amount}</div>
      </div>`;
  }).join('');
}

async function refreshAdvisor() {  const btn = document.getElementById('d-ai-refresh');
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
