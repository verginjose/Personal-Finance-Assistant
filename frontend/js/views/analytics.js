import { api, Auth, toast } from '../utils/api.js';
import { createDoughnut, createLine, createBar, destroyChart } from '../utils/charts.js';
import {
  pageHeader, healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData, skeletonChart
} from '../utils/ui.js';

let charts = [];

export async function renderAnalytics(container) {
  charts.forEach(c => destroyChart(c));
  charts = [];
  const userId = Auth.getUserId();

  container.innerHTML = `
    ${pageHeader('Analytics', 'Deep insights into your finances', '<button class="btn btn-secondary" id="a-export">Export CSV</button>')}
    <div class="card-grid card-grid-2 fade-up" style="margin-bottom:24px">
      ${healthPanelHtml('a')}
      ${aiPanelHtml('a')}
    </div>
    <div class="toolbar fade-up">
      <select class="form-select" id="a-timeline" style="width:160px" aria-label="Timeline granularity">
        <option value="MONTHLY">Monthly</option><option value="WEEKLY">Weekly</option><option value="DAILY">Daily</option>
      </select>
      <input class="form-input" id="a-start" type="date" aria-label="Start date">
      <input class="form-input" id="a-end" type="date" aria-label="End date">
      <button class="btn btn-primary btn-sm" id="a-apply">Apply Filters</button>
    </div>
    <div class="card-grid card-grid-2 fade-up">
      <div class="card">
        <div class="card-header"><h3>Expense Breakdown</h3></div>
        <div class="chart-container" id="a-expense-wrap">${skeletonChart()}</div>
      </div>
      <div class="card">
        <div class="card-header"><h3>Income Breakdown</h3></div>
        <div class="chart-container" id="a-income-wrap">${skeletonChart()}</div>
      </div>
    </div>
    <div class="card fade-up" style="margin-top:20px">
      <div class="card-header"><h3>Timeline Trends</h3></div>
      <div class="chart-container chart-container--tall" id="a-timeline-wrap">${skeletonChart(350)}</div>
    </div>
    <div class="card fade-up" style="margin-top:20px">
      <div class="card-header"><h3>Income by Category</h3></div>
      <div class="chart-container" id="a-bar-wrap">${skeletonChart()}</div>
    </div>`;

  if (window.flatpickr) {
    flatpickr('#a-start', { dateFormat: 'Y-m-d' });
    flatpickr('#a-end', { dateFormat: 'Y-m-d' });
  }

  document.getElementById('a-apply').onclick = () => loadAnalytics(userId);
  document.getElementById('a-export').onclick = async () => {
    try {
      const res = await api.get('/analytics/export', null, { raw: true });
      const blob = await res.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'transactions.csv';
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);
    } catch (err) {
      toast('Export failed: ' + err.message, 'error');
    }
  };
  loadAnalytics(userId);
}

async function loadAnalytics(userId) {
  charts.forEach(c => destroyChart(c));
  charts = [];
  const timelineType = document.getElementById('a-timeline')?.value || 'MONTHLY';
  const startDate = document.getElementById('a-start')?.value;
  const endDate = document.getElementById('a-end')?.value;
  const params = { userId, timelineType };
  if (startDate) params.startDate = startDate + 'T00:00:00';
  if (endDate) params.endDate = endDate + 'T23:59:59';

  try {
    const [expPie, incPie, timeline, health, ai] = await Promise.all([
      api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'EXPENSE' }),
      api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'INCOME' }),
      api.get('/analytics/timeline-chart', params),
      api.get('/analytics/health-score', { userId }),
      api.get('/analytics/ai-insights', { userId })
    ]);

    renderHealthData('a', health);
    renderAiData('a', ai);

    mountChart('a-expense-wrap', 'a-expense-pie', expPie, 'doughnut');
    mountChart('a-income-wrap', 'a-income-pie', incPie, 'doughnut');
    mountTimeline('a-timeline-wrap', 'a-timeline-chart', timeline);
    mountChart('a-bar-wrap', 'a-income-bar', incPie, 'bar');
  } catch (err) {
    toast('Analytics error: ' + err.message, 'error');
  }
}

function mountChart(wrapId, canvasId, data, type) {
  const wrap = document.getElementById(wrapId);
  if (!data?.labels?.length || !data.datasets?.[0]?.data) {
    wrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No data for this period</p></div>';
    return;
  }
  wrap.innerHTML = `<canvas id="${canvasId}"></canvas>`;
  const ctx = document.getElementById(canvasId);
  if (type === 'bar') {
    charts.push(createBar(ctx, data.labels, data.datasets[0].data));
  } else {
    charts.push(createDoughnut(ctx, data.labels, data.datasets[0].data));
  }
}

function mountTimeline(wrapId, canvasId, timeline) {
  const wrap = document.getElementById(wrapId);
  if (!timeline?.labels?.length || !timeline.datasets) {
    wrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No timeline data</p></div>';
    return;
  }
  wrap.innerHTML = `<canvas id="${canvasId}"></canvas>`;
  charts.push(createLine(document.getElementById(canvasId), timeline.labels, timeline.datasets));
}
