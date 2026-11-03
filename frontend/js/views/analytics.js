import { api, Auth, toast } from '../utils/api.js?v=2026110301';
import { createDoughnut, createLine, createBar, destroyChart } from '../utils/charts.js?v=2026110301';
import {
  pageHeader, healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData,
  skeletonChart, formatCurrency, formatCategory, esc
} from '../utils/ui.js?v=2026110301';

let charts = [];
let loadToken = 0;

export async function renderAnalytics(container) {
  charts.forEach(c => destroyChart(c));
  charts = [];
  const userId = Auth.getUserId();

  container.innerHTML = `
    ${pageHeader('Analytics', 'Deep insights into your finances', '<button class="btn btn-secondary" id="a-export">Export CSV</button>')}
    <div class="dashboard-top fade-up">
      ${healthPanelHtml('a')}
      ${aiPanelHtml('a')}
    </div>
    <div class="card-grid card-grid-4 fade-up" id="a-kpis" style="margin-bottom:20px" aria-live="polite"></div>
    <div class="analytics-toolbar fade-up" role="search" aria-label="Analytics filters">
      <div>
        <label for="a-timeline">View By</label>
        <select class="form-select" id="a-timeline" style="height:40px;" aria-label="Timeline granularity">
          <option value="MONTHLY">Monthly</option>
          <option value="WEEKLY">Weekly</option>
          <option value="DAILY">Daily</option>
          <option value="YEARLY">Yearly</option>
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
      <div class="analytics-actions">
        <button class="btn btn-primary" id="a-apply" style="height:40px;">Apply Filters</button>
        <button class="btn btn-ghost" id="a-clear" style="height:40px;">Clear</button>
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
      <div class="card-header">
        <h3>Timeline Trends</h3>
        <span class="card-subtitle" id="a-timeline-caption"></span>
      </div>
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
  document.getElementById('a-clear').onclick = () => {
    const startEl = document.getElementById('a-start');
    const endEl = document.getElementById('a-end');
    if (startEl) { if (startEl._flatpickr) startEl._flatpickr.clear(); else startEl.value = ''; }
    if (endEl) { if (endEl._flatpickr) endEl._flatpickr.clear(); else endEl.value = ''; }
    const timelineEl = document.getElementById('a-timeline');
    if (timelineEl) timelineEl.value = 'MONTHLY';
    loadAnalytics(userId);
  };

  document.getElementById('a-export').onclick = async () => {
    try {
      const res = await api.raw('GET', '/upsert/entries/export', { params: { userId } });
      if (!res.ok) throw new Error(`Request failed (${res.status})`);
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

/**
 * The API stores timestamps in UTC but the user filters by their own calendar
 * days. `tzOffsetMinutes` tells the backend the browser offset so the range is
 * translated to the right instants and the timeline buckets follow the user's
 * calendar (IST = +330).
 */
function browserTzOffsetMinutes() {
  return -new Date().getTimezoneOffset();
}

function buildParams() {
  const timelineType = document.getElementById('a-timeline')?.value || 'MONTHLY';
  const startDate = document.getElementById('a-start')?.value;
  const endDate = document.getElementById('a-end')?.value;
  const params = { userId: Auth.getUserId(), timelineType, tzOffsetMinutes: browserTzOffsetMinutes() };
  if (startDate) params.startDate = startDate + 'T00:00:00';
  // Inclusive upper bound: keep the last moment of the selected local day.
  if (endDate) params.endDate = endDate + 'T23:59:59.999';
  return params;
}

function describeRange() {
  const start = document.getElementById('a-start')?.value;
  const end = document.getElementById('a-end')?.value;
  if (!start && !end) return 'All time';
  if (start && end) return `${formatShortDate(start)} – ${formatShortDate(end)}`;
  return start ? `Since ${formatShortDate(start)}` : `Until ${formatShortDate(end)}`;
}

function formatShortDate(value) {
  const d = new Date(value + 'T00:00:00');
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function sumSeries(chart) {
  return (chart?.datasets?.[0]?.data || []).reduce((total, value) => total + (Number(value) || 0), 0);
}

function renderKpis(income, expense) {
  const wrap = document.getElementById('a-kpis');
  if (!wrap) return;
  const net = income - expense;
  const range = describeRange();
  const timelineLabel = document.getElementById('a-timeline')?.selectedOptions?.[0]?.textContent || 'Monthly';

  wrap.innerHTML = `
    <div class="card stat-card income">
      <div class="stat-card-top"><div class="stat-label">Income</div></div>
      <div class="stat-value">${formatCurrency(income)}</div>
    </div>
    <div class="card stat-card expense">
      <div class="stat-card-top"><div class="stat-label">Expenses</div></div>
      <div class="stat-value">${formatCurrency(expense)}</div>
    </div>
    <div class="card stat-card balance">
      <div class="stat-card-top"><div class="stat-label">Net</div></div>
      <div class="stat-value ${net < 0 ? 'is-negative' : ''}">${formatCurrency(net)}</div>
    </div>
    <div class="card stat-card">
      <div class="stat-card-top"><div class="stat-label">Range</div></div>
      <div class="stat-value stat-value--text">${esc(range)}</div>
      <div class="stat-hint">${esc(timelineLabel)} grouping</div>
    </div>`;
}

async function loadAnalytics(userId) {
  const token = ++loadToken;
  const applyBtn = document.getElementById('a-apply');
  if (applyBtn) { applyBtn.disabled = true; applyBtn.textContent = 'Applying…'; }

  charts.forEach(c => destroyChart(c));
  charts = [];

  const params = buildParams();
  const caption = document.getElementById('a-timeline-caption');
  if (caption) caption.textContent = `${params.timelineType.charAt(0) + params.timelineType.slice(1).toLowerCase()} · ${describeRange()}`;

  const [expResult, incResult] = await Promise.allSettled([
    api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'EXPENSE' }),
    api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'INCOME' }),
  ]);

  if (token !== loadToken) return; // A newer filter request superseded this one.

  const expPie = expResult.status === 'fulfilled' ? expResult.value : null;
  const incPie = incResult.status === 'fulfilled' ? incResult.value : null;

  if (expPie) {
    mountChart('a-expense-wrap', 'a-expense-pie', expPie, 'doughnut');
  } else {
    showError('a-expense-wrap', expResult.reason?.message);
  }

  if (incPie) {
    mountChart('a-income-wrap', 'a-income-pie', incPie, 'doughnut');
    mountChart('a-bar-wrap', 'a-income-bar', incPie, 'bar');
  } else {
    showError('a-income-wrap', incResult.reason?.message);
    showError('a-bar-wrap', incResult.reason?.message);
  }

  renderKpis(sumSeries(incPie), sumSeries(expPie));

  api.get('/analytics/timeline-chart', params)
    .then(timeline => { if (token === loadToken) mountTimeline('a-timeline-wrap', 'a-timeline-chart', timeline); })
    .catch(err => { if (token === loadToken) showError('a-timeline-wrap', err.message); });

  api.get('/analytics/health-score', { userId })
    .then(health => { if (token === loadToken) renderHealthData('a', health); })
    .catch(err => toast('Health Score error: ' + err.message, 'error'));

  api.get('/analytics/ai-insights', { userId })
    .then(ai => { if (token === loadToken) renderAiData('a', ai, () => refreshAdvisor(userId)); })
    .catch(err => {
      if (token !== loadToken) return;
      renderAiData('a', { error: err.message }, () => refreshAdvisor(userId));
      toast('AI Insights error: ' + err.message, 'error');
    });

  if (applyBtn) { applyBtn.disabled = false; applyBtn.textContent = 'Apply Filters'; }
}

function showError(wrapId, message) {
  const wrap = document.getElementById(wrapId);
  if (wrap) wrap.innerHTML = `<p style="color:var(--accent)">${esc(message || 'Could not load this chart')}</p>`;
}

async function refreshAdvisor(userId) {
  const btn = document.getElementById('a-ai-refresh');
  if (btn) btn.disabled = true;
  try {
    const ai = await api.get('/analytics/ai-insights', { userId, refresh: true });
    renderAiData('a', ai, () => refreshAdvisor(userId));
    toast('Advice refreshed', 'success');
  } catch (err) {
    renderAiData('a', { error: err.message }, () => refreshAdvisor(userId));
    toast('Could not refresh advice: ' + err.message, 'error');
  } finally {
    const current = document.getElementById('a-ai-refresh');
    if (current) current.disabled = false;
  }
}

function mountChart(wrapId, canvasId, data, type) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) return; // User navigated away
  if (!data?.labels?.length || !data.datasets?.[0]?.data?.length) {
    wrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No data for this period</p></div>';
    return;
  }
  // Category values arrive as enum keys (FOOD_DELIVERY); show readable names.
  const labels = data.labels.map(formatCategory);
  wrap.innerHTML = `<canvas id="${canvasId}"></canvas>`;
  const ctx = document.getElementById(canvasId);
  if (type === 'bar') {
    charts.push(createBar(ctx, labels, data.datasets[0].data));
  } else {
    charts.push(createDoughnut(ctx, labels, data.datasets[0].data));
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
