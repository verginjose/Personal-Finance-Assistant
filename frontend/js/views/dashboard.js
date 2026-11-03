import { api, Auth, toast } from '../utils/api.js?v=2026110305';
import { icon } from '../utils/icons.js?v=2026110305';
import {
  pageHeader,
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData, formatCurrency, alertBanner, esc
} from '../utils/ui.js?v=2026110305';

export async function renderDashboard(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Dashboard', 'Your financial overview at a glance')}
    <div id="d-alerts" style="display:flex;flex-direction:column;gap:10px;margin-bottom:20px;"></div>
    <div class="dashboard-top fade-up">
      ${healthPanelHtml('d')}
      ${aiPanelHtml('d')}
    </div>`;

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

    // Income, expense and transaction totals live on the Analytics page.

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
