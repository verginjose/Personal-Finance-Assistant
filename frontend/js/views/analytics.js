import { api, Auth, toast } from '../utils/api.js?v=1781338889';
import { createDoughnut, createLine, createBar, destroyChart } from '../utils/charts.js?v=1781338889';
import {
  pageHeader, healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData, skeletonChart
} from '../utils/ui.js?v=1781338889';

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
    <div class="analytics-toolbar fade-up">
      <div>
        <label for="a-timeline">View By</label>
        <select class="form-select" id="a-timeline" style="height:40px;" aria-label="Timeline granularity">
          <option value="MONTHLY">Monthly</option><option value="WEEKLY">Weekly</option><option value="DAILY">Daily</option>
        </select>
      </div>
      <div>
        <label for="a-start">From Date</label>
        <input class="form-input" id="a-start" type="date" style="height:40px;" aria-label="Start date">
      </div>
      <div>
        <label for="a-end">To Date</label>
        <input class="form-input" id="a-end" type="date" style="height:40px;" aria-label="End date">
      </div>
      <div>
        <label>&nbsp;</label>
        <button class="btn btn-primary" id="a-apply" style="height:40px;width:100%;">Apply Filters</button>
      </div>
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
    flatpickr('#a-start', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true });
    flatpickr('#a-end', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true });
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
  if (startDate) params.startDate = new Date(startDate + 'T00:00:00').toISOString();
  if (endDate) params.endDate = new Date(endDate + 'T23:59:59').toISOString();

  api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'EXPENSE' })
    .then(expPie => mountChart('a-expense-wrap', 'a-expense-pie', expPie, 'doughnut'))
    .catch(err => document.getElementById('a-expense-wrap').innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`);

  api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'INCOME' })
    .then(incPie => {
      mountChart('a-income-wrap', 'a-income-pie', incPie, 'doughnut');
      mountChart('a-bar-wrap', 'a-income-bar', incPie, 'bar');
    })
    .catch(err => {
      document.getElementById('a-income-wrap').innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`;
      document.getElementById('a-bar-wrap').innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`;
    });

  api.get('/analytics/timeline-chart', params)
    .then(timeline => mountTimeline('a-timeline-wrap', 'a-timeline-chart', timeline))
    .catch(err => document.getElementById('a-timeline-wrap').innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`);

  api.get('/analytics/health-score', { userId })
    .then(health => renderHealthData('a', health))
    .catch(err => toast('Health Score error: ' + err.message, 'error'));

  api.get('/analytics/ai-insights', { userId })
    .then(ai => renderAiData('a', ai))
    .catch(err => toast('AI Insights error: ' + err.message, 'error'));
}

function mountChart(wrapId, canvasId, data, type) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) return; // User navigated away
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
  if (!wrap) return; // User navigated away
  if (!timeline?.labels?.length || !timeline.datasets) {
    wrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No timeline data</p></div>';
    return;
  }
  wrap.innerHTML = `<canvas id="${canvasId}"></canvas>`;
  charts.push(createLine(document.getElementById(canvasId), timeline.labels, timeline.datasets));
}
