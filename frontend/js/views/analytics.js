import { api, Auth, toast } from '../utils/api.js?v=2026110417';
import { icon } from '../utils/icons.js?v=2026110417';
import { createLine, destroyChart } from '../utils/charts.js?v=2026110417';
import {
  pageHeader, skeletonChart, formatMoney, formatCategory, esc
} from '../utils/ui.js?v=2026110417';

let charts = [];
let loadToken = 0;

export async function renderAnalytics(container) {
  charts.forEach(c => destroyChart(c));
  charts = [];
  const userId = Auth.getUserId();

  container.innerHTML = `
    ${pageHeader('Analytics', '', '<button class="btn btn-secondary btn-sm" id="a-export">Export CSV</button>')}
    <div class="figures fade-up" id="a-kpis" aria-live="polite"></div>
    <div class="analytics-toolbar fade-up" role="search" aria-label="Analytics filters">
      <div>
        <label for="a-preset">Period</label>
        <select class="form-select" id="a-preset" aria-label="Date preset">
          <option value="ALL">All time</option>
          <option value="MONTH">This month</option>
          <option value="QUARTER">Last 3 months</option>
          <option value="YEAR">This year</option>
          <option value="CUSTOM">Custom</option>
        </select>
      </div>
      <div>
        <label for="a-timeline">Group by</label>
        <select class="form-select" id="a-timeline" aria-label="Timeline granularity">
          <option value="MONTHLY">Monthly</option>
          <option value="WEEKLY">Weekly</option>
          <option value="DAILY">Daily</option>
          <option value="YEARLY">Yearly</option>
        </select>
      </div>
      <div>
        <label for="a-start">From</label>
        <input class="form-input" id="a-start" type="date" aria-label="Start date">
      </div>
      <div>
        <label for="a-end">To</label>
        <input class="form-input" id="a-end" type="date" aria-label="End date">
      </div>
      <div class="analytics-actions">
        <button class="btn btn-primary" id="a-apply">Apply</button>
        <button class="btn btn-ghost" id="a-clear">Reset</button>
      </div>
    </div>
    <div class="card-grid card-grid-2 fade-up">
      <div class="card">
        <div class="card-header"><h3>Where your money went</h3></div>
        <div id="a-expense-wrap">${skeletonChart(220)}</div>
      </div>
      <div class="card">
        <div class="card-header"><h3>Where it came from</h3></div>
        <div id="a-income-wrap">${skeletonChart(220)}</div>
      </div>
    </div>
    <div class="card fade-up" style="margin-top:20px">
      <div class="card-header">
        <h3>Income and expenses</h3>
        <span class="card-subtitle" id="a-timeline-caption"></span>
      </div>
      <div class="chart-container chart-container--tall" id="a-timeline-wrap">${skeletonChart(320)}</div>
    </div>`;

  // Native <select> popups are painted by the OS/Chrome, which ignores the dark
  // UI. Tom Select keeps the same control but renders a themed dropdown.
  if (window.TomSelect) {
    new TomSelect('#a-timeline', { create: false, controlInput: null });
    new TomSelect('#a-preset', { create: false, controlInput: null });
  }
  if (window.flatpickr) {
    flatpickr('#a-start', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true, theme: 'dark' });
    flatpickr('#a-end', { dateFormat: 'Y-m-d', altInput: true, altFormat: 'F j, Y', disableMobile: true, theme: 'dark' });
  }

  const applyPreset = (value) => {
    const startEl = document.getElementById('a-start');
    const endEl = document.getElementById('a-end');
    if (!startEl || !endEl) return;
    const set = (el, val) => {
      if (el._flatpickr) el._flatpickr.setDate(val, true);
      else el.value = val;
    };
    const now = new Date();
    const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    if (value === 'ALL') { set(startEl, ''); set(endEl, ''); }
    if (value === 'MONTH') {
      set(startEl, iso(new Date(now.getFullYear(), now.getMonth(), 1)));
      set(endEl, iso(new Date(now.getFullYear(), now.getMonth() + 1, 0)));
    }
    if (value === 'QUARTER') {
      set(startEl, iso(new Date(now.getFullYear(), now.getMonth() - 2, 1)));
      set(endEl, iso(new Date(now.getFullYear(), now.getMonth() + 1, 0)));
    }
    if (value === 'YEAR') {
      set(startEl, iso(new Date(now.getFullYear(), 0, 1)));
      set(endEl, iso(new Date(now.getFullYear(), 11, 31)));
    }
  };

  const presetEl = document.getElementById('a-preset');
  if (presetEl) {
    presetEl.onchange = () => {
      applyPreset(presetEl.value);
      if (presetEl.value !== 'CUSTOM') { currentPageReset(); loadAnalytics(userId); }
    };
  }

  document.getElementById('a-apply').onclick = () => {
    if (presetEl && presetEl.tomselect) presetEl.tomselect.setValue('CUSTOM', true);
    loadAnalytics(userId);
  };
  document.getElementById('a-clear').onclick = () => {
    const startEl = document.getElementById('a-start');
    const endEl = document.getElementById('a-end');
    if (startEl) { if (startEl._flatpickr) startEl._flatpickr.clear(); else startEl.value = ''; }
    if (endEl) { if (endEl._flatpickr) endEl._flatpickr.clear(); else endEl.value = ''; }
    const timelineEl = document.getElementById('a-timeline');
    if (timelineEl) {
      if (timelineEl.tomselect) timelineEl.tomselect.setValue('MONTHLY', true);
      else timelineEl.value = 'MONTHLY';
    }
    if (presetEl) {
      if (presetEl.tomselect) presetEl.tomselect.setValue('ALL', true);
      else presetEl.value = 'ALL';
    }
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

function currentPageReset() { /* analytics has no pagination; kept for symmetry */ }

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

function renderKpis(income, expense, count) {
  const wrap = document.getElementById('a-kpis');
  if (!wrap) return;
  const net = income - expense;
  const currency = Auth.getDefaultCurrency();
  const range = describeRange();

  wrap.innerHTML = `
    <div class="figure">
      <div class="figure-label">Income</div>
      <div class="figure-value ${income > 0 ? 'is-pos' : ''}">${formatMoney(income, currency, { compact: true })}</div>
    </div>
    <div class="figure">
      <div class="figure-label">Expenses</div>
      <div class="figure-value">${formatMoney(expense, currency, { compact: true })}</div>
    </div>
    <div class="figure">
      <div class="figure-label">Net</div>
      <div class="figure-value ${net < 0 ? 'is-neg' : 'is-pos'}">${formatMoney(net, currency, { compact: true })}</div>
    </div>
    <div class="figure">
      <div class="figure-label">Transactions</div>
      <div class="figure-value">${count ?? 0} <span class="figure-hint">${esc(range)}</span></div>
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

  const summaryParams = { userId };
  if (params.startDate) summaryParams.startDate = params.startDate.slice(0, 10);
  if (params.endDate) summaryParams.endDate = params.endDate.slice(0, 10);

  const [expResult, incResult, summaryResult] = await Promise.allSettled([
    api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'EXPENSE' }),
    api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'INCOME' }),
    api.get('/upsert/summary', summaryParams),
  ]);

  if (token !== loadToken) return; // A newer filter request superseded this one.

  const expPie = expResult.status === 'fulfilled' ? expResult.value : null;
  const incPie = incResult.status === 'fulfilled' ? incResult.value : null;

  if (expPie) mountCategoryList('a-expense-wrap', expPie, 'Smaller categories');
  else showError('a-expense-wrap', expResult.reason?.message);

  if (incPie) mountCategoryList('a-income-wrap', incPie);
  else showError('a-income-wrap', incResult.reason?.message);

  const totalCount = summaryResult.status === 'fulfilled'
    ? Number(summaryResult.value?.totalCount) || 0
    : 0;
  renderKpis(sumSeries(incPie), sumSeries(expPie), totalCount);

  api.get('/analytics/timeline-chart', params)
    .then(timeline => { if (token === loadToken) mountTimeline('a-timeline-wrap', 'a-timeline-chart', timeline, params.timelineType); })
    .catch(err => { if (token === loadToken) showError('a-timeline-wrap', err.message); });

  if (applyBtn) { applyBtn.disabled = false; applyBtn.textContent = 'Apply'; }
}

function showError(wrapId, message) {
  const wrap = document.getElementById(wrapId);
  if (wrap) wrap.innerHTML = `<p style="color:var(--accent)">${esc(message || 'Could not load this chart')}</p>`;
}

/**
 * Sorted horizontal bars, top 6 plus a folded remainder. One hue, values and
 * percentages printed on the row, so no legend is needed.
 */
function mountCategoryList(wrapId, data, foldLabel) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) return;
  const labels = data?.labels || [];
  const values = data?.datasets?.[0]?.data || [];
  if (!labels.length || !values.length) {
    wrap.innerHTML = '<div class="empty-state" style="padding:30px"><p>No data for this period</p></div>';
    return;
  }
  const currency = Auth.getDefaultCurrency();
  const rows = labels
    .map((label, i) => ({ label: formatCategory(label), value: Number(values[i]) || 0 }))
    .filter(r => r.value > 0)
    .sort((a, b) => b.value - a.value);

  const total = rows.reduce((sum, r) => sum + r.value, 0);
  if (total <= 0) {
    wrap.innerHTML = '<div class="empty-state" style="padding:30px"><p>No data for this period</p></div>';
    return;
  }
  const max = rows[0].value;
  const top = rows.slice(0, 6);
  const rest = rows.slice(6);
  const list = [...top];
  if (rest.length) {
    list.push({ label: foldLabel || 'Other', value: rest.reduce((s, r) => s + r.value, 0), folded: true });
  }

  wrap.innerHTML = `
    <div class="barlist">
      ${list.map(r => {
        const pct = total > 0 ? (r.value / total) * 100 : 0;
        const width = max > 0 ? Math.max(2, (r.value / max) * 100) : 0;
        return `
          <div class="barlist-row">
            <div class="barlist-label">${esc(r.label)}</div>
            <div class="barlist-track"><div class="barlist-fill" style="width:${width.toFixed(1)}%"></div></div>
            <div class="barlist-value">${formatMoney(r.value, currency, { compact: true })}<span>${pct.toFixed(0)}%</span></div>
          </div>`;
      }).join('')}
    </div>
    <div class="barlist-total">Total ${formatMoney(total, currency, { compact: true })}</div>`;
}

/**
 * Category axes only show the months that exist in the data; a month with no
 * rows is a real zero, not a gap. Expand the label list so the line never
 * jumps from 2016 to 2026.
 */
function fillMonthlyGaps(labels, datasets) {
  const months = labels.map(l => /^\d{4}-\d{2}$/.test(String(l)) ? String(l) : null);
  if (months.some(m => m === null)) return { labels, datasets };

  const parse = (m) => { const [y, mo] = m.split('-').map(Number); return y * 12 + (mo - 1); };
  const format = (n) => `${Math.floor(n / 12)}-${String((n % 12) + 1).padStart(2, '0')}`;
  const points = labels.map((l, i) => ({ idx: parse(l), i })).sort((a, b) => a.idx - b.idx);
  const first = points[0].idx;
  const last = points[points.length - 1].idx;
  if (last - first > 240) return { labels, datasets }; // do not fabricate two decades

  const denseLabels = [];
  for (let n = first; n <= last; n++) denseLabels.push(format(n));

  const filled = datasets.map(ds => ({ ...ds, data: denseLabels.map(label => {
    const found = points.find(p => format(p.idx) === label);
    return found ? ds.data[found.i] : 0;
  }) }));
  return { labels: denseLabels, datasets: filled };
}

function mountTimeline(wrapId, canvasId, timeline, timelineType) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) return; // User navigated away
  if (!timeline?.labels?.length || !timeline.datasets) {
    wrap.innerHTML = '<div class="empty-state" style="padding:40px"><p>No timeline data</p></div>';
    return;
  }
  const series = timelineType === 'MONTHLY'
    ? fillMonthlyGaps(timeline.labels, timeline.datasets)
    : { labels: timeline.labels, datasets: timeline.datasets };
  wrap.innerHTML = `<canvas id="${canvasId}"></canvas>`;
  charts.push(createLine(document.getElementById(canvasId), series.labels, series.datasets));
}
