import { api, Auth, toast } from '../utils/api.js';
import { createDoughnut, createLine, destroyChart } from '../utils/charts.js';
import {
  pageHeader, skeletonKpiRow, skeletonChart,
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData, formatCurrency
} from '../utils/ui.js';

let pieChart = null, lineChart = null;

export async function renderDashboard(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Dashboard', 'Your financial overview at a glance')}
    <div class="card-grid card-grid-2 fade-up" style="margin-bottom:24px">
      ${healthPanelHtml('d')}
      ${aiPanelHtml('d')}
    </div>
    <div id="d-kpis">${skeletonKpiRow(4)}</div>
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
    const [summary, pie, timeline, health, ai] = await Promise.all([
      api.get('/upsert/summary', { userId }),
      api.get('/analytics/category-pie-chart', { userId, transactionFilter: 'EXPENSE' }),
      api.get('/analytics/timeline-chart', { userId, timelineType: 'MONTHLY' }),
      api.get('/analytics/health-score', { userId }),
      api.get('/analytics/ai-insights', { userId })
    ]);

    document.getElementById('d-kpis').innerHTML = `
      <div class="card-grid card-grid-4 fade-up">
        <div class="card stat-card income">
          <div class="stat-card-top"><div class="stat-label">Total Income</div><div class="stat-icon">📈</div></div>
          <div class="stat-value">${formatCurrency(summary.totalIncome)}</div>
        </div>
        <div class="card stat-card expense">
          <div class="stat-card-top"><div class="stat-label">Total Expenses</div><div class="stat-icon">📉</div></div>
          <div class="stat-value">${formatCurrency(summary.totalExpense)}</div>
        </div>
        <div class="card stat-card balance">
          <div class="stat-card-top"><div class="stat-label">Net Balance</div><div class="stat-icon">💎</div></div>
          <div class="stat-value">${formatCurrency(summary.netBalance)}</div>
        </div>
        <div class="card stat-card count">
          <div class="stat-card-top"><div class="stat-label">Transactions</div><div class="stat-icon">🧾</div></div>
          <div class="stat-value">${summary.totalCount ?? 0}</div>
        </div>
      </div>`;

    renderHealthData('d', health);
    renderAiData('d', ai);

    const pieWrap = document.getElementById('d-pie-wrap');
    pieWrap.innerHTML = '<canvas id="d-pie"></canvas>';
    pieChart = destroyChart(pieChart);
    if (pie?.labels?.length && pie.datasets?.[0]?.data) {
      pieChart = createDoughnut(document.getElementById('d-pie'), pie.labels, pie.datasets[0].data);
    } else {
      pieWrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No expense data yet</p></div>';
    }

    const lineWrap = document.getElementById('d-line-wrap');
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
