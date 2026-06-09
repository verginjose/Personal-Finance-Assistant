import { api, Auth, toast } from '../utils/api.js';
import { createDoughnut, createLine, destroyChart } from '../utils/charts.js';

let pieChart = null, lineChart = null;

export async function renderDashboard(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    <div class="page-header fade-up"><h1>Dashboard</h1><p>Your financial overview at a glance</p></div>
    <div class="card-grid card-grid-4 fade-up" style="animation-delay:.05s">
      <div class="card stat-card income"><div class="stat-icon">📈</div><div class="stat-value" id="d-income">—</div><div class="stat-label">Income</div></div>
      <div class="card stat-card expense"><div class="stat-icon">📉</div><div class="stat-value" id="d-expense">—</div><div class="stat-label">Expenses</div></div>
      <div class="card stat-card balance"><div class="stat-icon">💎</div><div class="stat-value" id="d-balance">—</div><div class="stat-label">Net Balance</div></div>
      <div class="card stat-card count"><div class="stat-icon">🧾</div><div class="stat-value" id="d-count">—</div><div class="stat-label">Transactions</div></div>
    </div>
    <div class="card-grid card-grid-2" style="margin-top:24px">
      <div class="card fade-up" style="animation-delay:.1s"><h3 style="margin-bottom:16px;font-weight:600">Spending by Category</h3><div class="chart-container"><canvas id="d-pie"></canvas></div></div>
      <div class="card fade-up" style="animation-delay:.15s"><h3 style="margin-bottom:16px;font-weight:600">Monthly Timeline</h3><div class="chart-container"><canvas id="d-line"></canvas></div></div>
    </div>`;

  try {
    const [summary, pie, timeline] = await Promise.all([
      api.get('/upsert/summary', { userId }),
      api.get('/analytics/category-pie-chart', { userId, transactionFilter: 'EXPENSE' }),
      api.get('/analytics/timeline-chart', { userId, timelineType: 'MONTHLY' })
    ]);

    const fmt = n => '₹' + Number(n||0).toLocaleString('en-IN', { maximumFractionDigits: 0 });
    document.getElementById('d-income').textContent  = fmt(summary.totalIncome);
    document.getElementById('d-expense').textContent = fmt(summary.totalExpense);
    document.getElementById('d-balance').textContent = fmt(summary.netBalance);
    document.getElementById('d-count').textContent   = summary.transactionCount ?? '0';

    pieChart = destroyChart(pieChart);
    if (pie?.labels?.length && pie.datasets?.[0]?.data) {
      pieChart = createDoughnut(document.getElementById('d-pie'), pie.labels, pie.datasets[0].data);
    }

    lineChart = destroyChart(lineChart);
    if (timeline?.labels?.length && timeline.datasets) {
      lineChart = createLine(document.getElementById('d-line'), timeline.labels, timeline.datasets);
    }
  } catch (err) {
    toast('Failed to load dashboard: ' + err.message, 'error');
  }
}
