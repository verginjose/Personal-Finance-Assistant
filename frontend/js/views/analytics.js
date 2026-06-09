import { api, Auth, toast } from '../utils/api.js';
import { createDoughnut, createLine, createBar, destroyChart } from '../utils/charts.js';

let charts = [];

export async function renderAnalytics(container) {
  charts.forEach(c => destroyChart(c)); charts = [];
  const userId = Auth.getUserId();
  container.innerHTML = `
    <div class="page-header fade-up"><h1>Analytics</h1><p>Deep insights into your finances</p></div>
    <div class="toolbar fade-up" style="animation-delay:.05s">
      <select class="form-select" id="a-timeline" style="width:160px"><option value="MONTHLY">Monthly</option><option value="WEEKLY">Weekly</option><option value="DAILY">Daily</option></select>
      <input class="form-input" id="a-start" type="date" style="width:160px">
      <input class="form-input" id="a-end" type="date" style="width:160px">
      <button class="btn btn-primary btn-sm" id="a-apply">Apply</button>
    </div>
    <div class="card-grid card-grid-2 fade-up" style="animation-delay:.1s">
      <div class="card"><h3 style="margin-bottom:16px;font-weight:600">Expense Breakdown</h3><div class="chart-container"><canvas id="a-expense-pie"></canvas></div></div>
      <div class="card"><h3 style="margin-bottom:16px;font-weight:600">Income Breakdown</h3><div class="chart-container"><canvas id="a-income-pie"></canvas></div></div>
    </div>
    <div class="card fade-up" style="margin-top:20px;animation-delay:.15s"><h3 style="margin-bottom:16px;font-weight:600">Timeline Trends</h3><div class="chart-container" style="height:350px"><canvas id="a-timeline-chart"></canvas></div></div>
    <div class="card fade-up" style="margin-top:20px;animation-delay:.2s"><h3 style="margin-bottom:16px;font-weight:600">Income by Category</h3><div class="chart-container" style="height:300px"><canvas id="a-income-bar"></canvas></div></div>`;

  document.getElementById('a-apply').onclick = () => loadAnalytics(userId);
  loadAnalytics(userId);
}

async function loadAnalytics(userId) {
  charts.forEach(c => destroyChart(c)); charts = [];
  const timelineType = document.getElementById('a-timeline')?.value || 'MONTHLY';
  const startDate = document.getElementById('a-start')?.value;
  const endDate = document.getElementById('a-end')?.value;
  const params = { userId, timelineType };
  if (startDate) params.startDate = startDate + 'T00:00:00';
  if (endDate) params.endDate = endDate + 'T23:59:59';

  try {
    const [expPie, incPie, timeline] = await Promise.all([
      api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'EXPENSE' }),
      api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'INCOME' }),
      api.get('/analytics/timeline-chart', params)
    ]);

    if (expPie?.labels?.length && expPie.datasets?.[0]?.data) {
      charts.push(createDoughnut(document.getElementById('a-expense-pie'), expPie.labels, expPie.datasets[0].data));
    }
    if (incPie?.labels?.length && incPie.datasets?.[0]?.data) {
      charts.push(createDoughnut(document.getElementById('a-income-pie'), incPie.labels, incPie.datasets[0].data));
    }
    if (timeline?.labels?.length && timeline.datasets) {
      charts.push(createLine(document.getElementById('a-timeline-chart'), timeline.labels, timeline.datasets));
    }

    // Income by category bar
    if (incPie?.labels?.length && incPie.datasets?.[0]?.data) {
      charts.push(createBar(document.getElementById('a-income-bar'), incPie.labels, incPie.datasets[0].data));
    }
  } catch (err) { toast('Analytics error: ' + err.message, 'error'); }
}
