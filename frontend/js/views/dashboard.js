import { api, Auth, toast } from '../utils/api.js';
import { createDoughnut, createLine, destroyChart } from '../utils/charts.js';
import { navigateTo } from '../app.js';
import { icon } from '../utils/icons.js';
import {
  pageHeader, skeletonKpiRow, skeletonChart,
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData, formatCurrency
} from '../utils/ui.js';

let pieChart = null, lineChart = null;

export async function renderDashboard(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Dashboard', 'Your financial overview at a glance')}
    <div id="d-alerts" style="display:flex;flex-direction:column;gap:10px;margin-bottom:20px;"></div>
    <div class="card-grid card-grid-2 fade-up" style="margin-bottom:24px">
      ${healthPanelHtml('d')}
      ${aiPanelHtml('d')}
    </div>
    <div id="d-kpis">${skeletonKpiRow(4)}</div>
    <div id="d-goals" class="card-grid fade-up" style="margin-top:24px;display:none;grid-template-columns:repeat(auto-fit, minmax(300px, 1fr));gap:20px;"></div>
    <div class="card-grid card-grid-2" style="margin-top:24px">
      <div class="card fade-up">
        <div class="card-header"><h3>Spending by Category</h3></div>
        <div class="chart-container" id="d-pie-wrap">${skeletonChart()}</div>
      </div>
      <div class="card fade-up">
        <div class="card-header"><h3>Monthly Timeline</h3></div>
        <div class="chart-container" id="d-line-wrap">${skeletonChart()}</div>
      </div>
    </div>`;

  try {
    const [summary, pie, timeline, health, ai, budgets, goals] = await Promise.all([
      api.get('/upsert/summary', { userId }),
      api.get('/analytics/category-pie-chart', { userId, transactionFilter: 'EXPENSE' }),
      api.get('/analytics/timeline-chart', { userId, timelineType: 'MONTHLY' }),
      api.get('/analytics/health-score', { userId }),
      api.get('/analytics/ai-insights', { userId }),
      api.get('/upsert/budgets', { userId }).catch(() => []),
      api.get('/upsert/goals', { userId }).catch(() => [])
    ]);

    // Budget Alerts
    const alertsContainer = document.getElementById('d-alerts');
    if (!alertsContainer) return; // User navigated away
    const alertsHtml = [];
    for (const b of budgets) {
        if (b.utilizationPercentage >= 100) {
            alertsHtml.push(`<div style="background:rgba(239,68,68,0.1);border:1px solid rgba(239,68,68,0.3);color:var(--accent);padding:12px 16px;border-radius:8px;display:flex;align-items:center;gap:12px;">${icon('alert-triangle')} <strong>Budget Exceeded:</strong> You have spent ${b.utilizationPercentage.toFixed(1)}% of your ${b.expenseCategory} budget!</div>`);
        } else if (b.utilizationPercentage >= 90) {
            alertsHtml.push(`<div style="background:rgba(245,158,11,0.1);border:1px solid rgba(245,158,11,0.3);color:var(--accent-y);padding:12px 16px;border-radius:8px;display:flex;align-items:center;gap:12px;">${icon('alert-triangle')} <strong>Critical Warning:</strong> You have spent ${b.utilizationPercentage.toFixed(1)}% of your ${b.expenseCategory} budget.</div>`);
        } else if (b.utilizationPercentage >= 80) {
            alertsHtml.push(`<div style="background:rgba(245,158,11,0.1);border:1px solid rgba(245,158,11,0.3);color:var(--accent-y);padding:12px 16px;border-radius:8px;display:flex;align-items:center;gap:12px;">${icon('alert-circle')} <strong>Warning:</strong> You are nearing your ${b.expenseCategory} budget limit (${b.utilizationPercentage.toFixed(1)}%).</div>`);
        }
    }
    if (alertsHtml.length > 0) {
        alertsContainer.innerHTML = alertsHtml.join('');
    }

    const kpisContainer = document.getElementById('d-kpis');
    if (kpisContainer) {
      kpisContainer.innerHTML = `
      <div class="card-grid card-grid-4 fade-up">
        <div class="card stat-card income">
          <div class="stat-card-top"><div class="stat-label">Total Income</div><div class="stat-icon">${icon('trending-up')}</div></div>
          <div class="stat-value">${formatCurrency(summary.totalIncome)}</div>
        </div>
        <div class="card stat-card expense">
          <div class="stat-card-top"><div class="stat-label">Total Expenses</div><div class="stat-icon">${icon('trending-down')}</div></div>
          <div class="stat-value">${formatCurrency(summary.totalExpense)}</div>
        </div>
        <div class="card stat-card balance">
          <div class="stat-card-top"><div class="stat-label">Net Balance</div><div class="stat-icon">${icon('wallet')}</div></div>
          <div class="stat-value">${formatCurrency(summary.netBalance)}</div>
        </div>
        <div class="card stat-card count">
          <div class="stat-card-top"><div class="stat-label">Transactions</div><div class="stat-icon">${icon('list')}</div></div>
          <div class="stat-value">${summary.totalCount ?? 0}</div>
        </div>
      </div>`;
    }

    // Goal Forecasting
    const goalsContainer = document.getElementById('d-goals');
    if (goalsContainer) {
      goalsContainer.style.display = 'grid';
    const activeGoals = goals.filter(g => !g.completed);
    
    if (activeGoals.length > 0) {
        let goalsHtml = '';
        for (const g of activeGoals) {
            try {
                const forecast = await api.get(`/analytics/goals/${g.id}/forecast`);
                let velocityText = forecast.monthlyVelocity > 0 ? `<br><small style="color:var(--text-dim)">Velocity: ${formatCurrency(forecast.monthlyVelocity, g.currency)}/mo</small>` : '';
                goalsHtml += `
                <div class="card" style="border-left:4px solid var(--accent-g)">
                    <div style="font-weight:600;margin-bottom:4px;">🎯 ${g.name}</div>
                    <div style="font-size:0.9rem;color:var(--text-muted);">${forecast.message}${velocityText}</div>
                </div>`;
            } catch (e) { console.error('Failed to forecast goal', g.id); }
        }
        goalsContainer.innerHTML = goalsHtml;
    } else {
        goalsContainer.innerHTML = `
            <div class="card" style="border-left:4px solid var(--border); grid-column: 1 / -1; display:flex; align-items:center; gap: 16px; padding: 20px;">
                <div style="background:var(--bg-lighter); width:48px; height:48px; border-radius:50%; display:flex; align-items:center; justify-content:center; color:var(--text-dim);">
                    ${icon('target')}
                </div>
                <div>
                    <div style="font-weight:600;margin-bottom:4px;">No Active Goals</div>
                    <div style="font-size:0.9rem;color:var(--text-muted);">Create a savings goal in the Goals & Budgets page to track your progress and get AI forecasts!</div>
                </div>
            </div>`;
        }
    }

    renderHealthData('d', health);
    renderAiData('d', ai);

    const pieWrap = document.getElementById('d-pie-wrap');
    if (!pieWrap) return;
    pieWrap.innerHTML = '<canvas id="d-pie"></canvas>';
    pieChart = destroyChart(pieChart);
    if (pie?.labels?.length && pie.datasets?.[0]?.data) {
      pieChart = createDoughnut(document.getElementById('d-pie'), pie.labels, pie.datasets[0].data, '', (label, value) => {
        // Find the category key matching the label
        // Labels are typically formatted, e.g. 'Food and Dining' -> 'FOOD_AND_DINING'
        const categoryKey = label.toUpperCase().replace(/\s+/g, '_');
        
        // Wait, how do we pass filter to transactions page?
        // We'll set it in localStorage briefly or just navigate
        // Since we don't have a state management system, we can store it in sessionStorage
        sessionStorage.setItem('pendingCategoryFilter', categoryKey);
        navigateTo('transactions');
      });
    } else {
      pieWrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No expense data yet</p></div>';
    }

    const lineWrap = document.getElementById('d-line-wrap');
    if (!lineWrap) return;
    lineWrap.innerHTML = '<canvas id="d-line"></canvas>';
    lineChart = destroyChart(lineChart);
    if (timeline?.labels?.length && timeline.datasets) {
      lineChart = createLine(document.getElementById('d-line'), timeline.labels, timeline.datasets);
    } else {
      lineWrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No timeline data yet</p></div>';
    }
  } catch (err) {
    toast('Failed to load dashboard: ' + err.message, 'error');
  }
}
