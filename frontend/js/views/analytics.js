import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import { createBarChart, destroyChart } from '../utils/charts.js';
import { getTotals } from '../utils/totals.js';
import {
  pageHeader, skeletonChart, formatMoney, formatCompact, formatCategory, formatMonth,
  formatDate, esc,
} from '../utils/ui.js';

let charts = [];
let loadToken = 0;
let showIncome = true;
let showExpense = true;
let showNet = true;
let expensesOnly = false;
let lastTimeline = null;
/** Latest KPI figures, so a width change (rotation) re-checks compaction. */
let lastKpis = null;
let kpiObserver = null;

const DEFAULT_FILTERS = { preset: 'ALL', timeline: 'MONTHLY', range: '' };

export async function renderAnalytics(container) {
  charts.forEach(c => destroyChart(c));
  charts = [];
  if (kpiObserver) { kpiObserver.disconnect(); kpiObserver = null; }
  lastKpis = null;
  showIncome = true; showExpense = true; showNet = true; expensesOnly = false;

  const userId = Auth.getUserId();

  container.innerHTML = `
    ${pageHeader('Analytics', '', `<button class="btn btn-ghost btn-sm" id="a-export">${icon('download', 'sm')} Export CSV</button>`)}
    <div class="figures figures--kpi" id="a-kpis" aria-live="polite">
      <div class="figure figure--net">
        <div class="figure-label">Net</div>
        <div class="figure-value figure-value--net" id="a-net">—</div>
      </div>
      <div class="figure">
        <div class="figure-label">Income</div>
        <div class="figure-value" id="a-income">—</div>
      </div>
      <div class="figure">
        <div class="figure-label">Expenses <span class="figure-note figure-note--inline" title="Payments between group members are not counted as spending.">Settlements excluded</span></div>
        <div class="figure-value" id="a-expense">—</div>
        <div class="figure-note figure-note--caption" title="Payments between group members are not counted as spending.">Settlements excluded</div>
      </div>
      <div class="figure">
        <div class="figure-label">Transactions <span class="figure-hint-inline" id="a-count-scope"></span></div>
        <div class="figure-value" id="a-count">—</div>
      </div>
    </div>

    <div class="analytics-toolbar" role="search" aria-label="Analytics filters">
      <div class="analytics-presets-wrap">
        <span class="analytics-toolbar-label">Quick range</span>
        <div class="preset-chips" id="a-presets" role="group" aria-label="Quick date ranges">
          <button type="button" class="chart-toggle" data-preset="LAST7">Last 7 days</button>
          <button type="button" class="chart-toggle" data-preset="MONTH">This month</button>
          <button type="button" class="chart-toggle" data-preset="LASTMONTH">Last month</button>
          <button type="button" class="chart-toggle" data-preset="QUARTER">Last 3 months</button>
          <button type="button" class="chart-toggle" data-preset="YEAR">This year</button>
          <button type="button" class="chart-toggle" data-preset="ALL">All time</button>
        </div>
      </div>
      <div class="analytics-field analytics-field--period">
        <label for="a-preset">Period</label>
        <select class="form-select" id="a-preset" aria-label="Date preset">
          <option value="ALL">All time</option>
          <option value="MONTH">This month</option>
          <option value="QUARTER">Last 3 months</option>
          <option value="YEAR">This year</option>
          <option value="CUSTOM">Custom range</option>
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
      <div class="analytics-field analytics-field--range">
        <label for="a-range">Date range</label>
        <div class="analytics-range-wrap">
          <input class="form-input" id="a-range" type="text" placeholder="All time" aria-label="Date range" autocomplete="off">
          <span class="analytics-range-icon" aria-hidden="true">${icon('calendar', 'sm')}</span>
        </div>
      </div>
      <button type="button" class="analytics-reset" id="a-clear" hidden>Reset</button>
    </div>

    <div class="card-grid card-grid-2">
      <section class="section-card">
        <div class="section-card-header"><h3>Where your money went</h3></div>
        <div class="section-card-body" id="a-expense-wrap">${skeletonChart(220)}</div>
      </section>
      <section class="section-card">
        <div class="section-card-header"><h3>Where it came from</h3></div>
        <div class="section-card-body" id="a-income-wrap">${skeletonChart(220)}</div>
      </section>
    </div>

    <section class="section-card" style="margin-top:var(--s4)">
      <div class="section-card-header">
        <h3>Income and expenses</h3>
        <span class="card-subtitle" id="a-timeline-caption"></span>
      </div>
      <div class="section-card-body">
        <div class="chart-legend" id="a-legend">
          <button type="button" class="chart-toggle is-on" data-series="income" aria-pressed="true"><span class="chart-toggle-swatch" style="color:var(--pos)"></span>Income</button>
          <button type="button" class="chart-toggle is-on" data-series="expense" aria-pressed="true"><span class="chart-toggle-swatch" style="color:var(--text-2)"></span>Expenses</button>
          <button type="button" class="chart-toggle is-on" data-series="net" aria-pressed="true"><span class="chart-toggle-swatch" style="color:var(--brand)"></span>Net line</button>
          <button type="button" class="chart-toggle chart-toggle--mode" data-series="expenses-only" aria-pressed="false" aria-label="View mode: expenses only">Expenses only</button>
        </div>
        <div class="chart-container chart-container--tall" id="a-timeline-wrap">${skeletonChart(320)}</div>
      </div>
    </section>`;

  // Searchable selects, themed.
  if (window.TomSelect) {
    ['#a-timeline', '#a-preset'].forEach(sel => {
      const el = document.querySelector(sel);
      if (el && !el.tomselect) new TomSelect(sel, { create: false, controlInput: null });
    });
  }
  if (window.flatpickr) {
    flatpickr('#a-range', {
      mode: 'range',
      dateFormat: 'Y-m-d',
      altInput: true,
      altFormat: 'j M Y',
      altInputClass: 'form-input analytics-range-input',
      disableMobile: true,
      position: 'below',
      onChange: () => {
        const { start, end } = readRange();
        if (start || end) {
          const preset = document.getElementById('a-preset');
          if (preset) {
            if (preset.tomselect) preset.tomselect.setValue('CUSTOM', true);
            else preset.value = 'CUSTOM';
          }
        }
        syncPresetChips();
        scheduleLoad();
      },
      onReady: (_, __, fp) => {
        if (fp) {
          fp.clear();
          if (fp.element) fp.element.value = '';
          if (fp.altInput) fp.altInput.value = 'All time';
        }
      },
    });
  } else {
    setRangeDisplayEmpty();
  }

  const presetEl = document.getElementById('a-preset');
  const timelineEl = document.getElementById('a-timeline');

  const onFilterChange = () => {
    updateResetVisibility();
    scheduleLoad();
  };

  presetEl?.addEventListener('change', () => {
    applyPreset(presetEl.value);
    onFilterChange();
  });
  timelineEl?.addEventListener('change', onFilterChange);
  document.getElementById('a-range')?.addEventListener('change', () => {
    if (presetEl) setSelect('a-preset', 'CUSTOM');
    syncPresetChips();
    onFilterChange();
  });

  document.querySelectorAll('#a-presets [data-preset]').forEach(btn => {
    btn.onclick = () => {
      const id = btn.dataset.preset;
      applyPreset(id);
      const map = { MONTH: 'MONTH', QUARTER: 'QUARTER', YEAR: 'YEAR', ALL: 'ALL' };
      setSelect('a-preset', map[id] || 'CUSTOM');
      syncPresetChips();
      onFilterChange();
    };
  });

  document.getElementById('a-clear').onclick = () => {
    setSelect('a-preset', DEFAULT_FILTERS.preset);
    setSelect('a-timeline', DEFAULT_FILTERS.timeline);
    setRangeDisplayEmpty();
    syncPresetChips();
    updateResetVisibility();
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

  wireLegend(userId);
  updateResetVisibility();
  syncPresetChips();
  // Rotation or a window resize changes the KPI column width; re-check whether
  // the full figures still fit instead of leaving a stale compact form.
  if (kpiObserver) kpiObserver.disconnect();
  if (typeof ResizeObserver !== 'undefined') {
    const kpis = document.getElementById('a-kpis');
    if (kpis) {
      kpiObserver = new ResizeObserver(() => {
        if (!lastKpis) return;
        const { income, expense, count, net } = lastKpis;
        renderKpis(income, expense, count, net);
      });
      kpiObserver.observe(kpis);
    }
  }
  // The range control must not look focused before the user touches it.
  requestAnimationFrame(() => {
    const range = document.getElementById('a-range');
    if (!range) return;
    range.blur();
    range._flatpickr?.altInput?.blur();
  });
  loadAnalytics(userId);
}

let debounceTimer;
function scheduleLoad() {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => loadAnalytics(Auth.getUserId()), 300);
}

function setSelect(id, value) {
  const el = document.getElementById(id);
  if (!el) return;
  if (el.tomselect) el.tomselect.setValue(value, true);
  else el.value = value;
}

function applyPreset(value) {
  const range = document.getElementById('a-range');
  if (!range) return;
  const now = new Date();
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  let start = '', end = '';
  if (value === 'LAST7') {
    start = iso(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6));
    end = iso(now);
  } else if (value === 'MONTH') {
    start = iso(new Date(now.getFullYear(), now.getMonth(), 1));
    end = iso(new Date(now.getFullYear(), now.getMonth() + 1, 0));
  } else if (value === 'LASTMONTH') {
    start = iso(new Date(now.getFullYear(), now.getMonth() - 1, 1));
    end = iso(new Date(now.getFullYear(), now.getMonth(), 0));
  } else if (value === 'QUARTER') {
    start = iso(new Date(now.getFullYear(), now.getMonth() - 2, 1));
    end = iso(new Date(now.getFullYear(), now.getMonth() + 1, 0));
  } else if (value === 'YEAR') {
    start = iso(new Date(now.getFullYear(), 0, 1));
    end = iso(new Date(now.getFullYear(), 11, 31));
  }

  if (range._flatpickr) {
    if (start && end) range._flatpickr.setDate([start, end], true);
    else setRangeDisplayEmpty();
  } else {
    range.value = start && end ? `${start} to ${end}` : '';
    if (!start || !end) setRangeDisplayEmpty();
  }
}

/** The empty range control says "All time"; it is a value, not a placeholder. */
function setRangeDisplayEmpty() {
  const range = document.getElementById('a-range');
  if (!range) return;
  const fp = range._flatpickr;
  if (fp) {
    fp.clear();
    range.value = '';
    if (fp.altInput) fp.altInput.value = 'All time';
  } else {
    // Before flatpickr attaches: the control reads "All time", not a placeholder.
    range.value = 'All time';
  }
}

function syncPresetChips() {
  const range = document.getElementById('a-range');
  const { start, end } = readRange();
  const now = new Date();
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const matches = {
    LAST7: start === iso(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 6)) && end === iso(now),
    MONTH: start === iso(new Date(now.getFullYear(), now.getMonth(), 1)) && end === iso(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
    LASTMONTH: start === iso(new Date(now.getFullYear(), now.getMonth() - 1, 1)) && end === iso(new Date(now.getFullYear(), now.getMonth(), 0)),
    QUARTER: start === iso(new Date(now.getFullYear(), now.getMonth() - 2, 1)) && end === iso(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
    YEAR: start === iso(new Date(now.getFullYear(), 0, 1)) && end === iso(new Date(now.getFullYear(), 11, 31)),
  };
  const allTime = !start && !end;
  document.querySelectorAll('#a-presets [data-preset]').forEach(btn => {
    const id = btn.dataset.preset;
    const on = id === 'ALL' ? allTime : !!matches[id];
    btn.classList.toggle('is-on', on);
    btn.setAttribute('aria-pressed', String(on));
  });
  if (allTime && range?._flatpickr?.altInput) range._flatpickr.altInput.value = 'All time';
  scrollActivePresetIntoView();
}

/** Centre the selected quick range without ever scrolling the page itself. */
function scrollActivePresetIntoView() {
  const scroller = document.getElementById('a-presets');
  if (!scroller || scroller.scrollWidth <= scroller.clientWidth) return;
  const active = scroller.querySelector('.is-on');
  if (!active) return;
  const left = active.getBoundingClientRect().left - scroller.getBoundingClientRect().left + scroller.scrollLeft;
  const target = left - (scroller.clientWidth - active.offsetWidth) / 2;
  scroller.scrollLeft = Math.max(0, target);
}

function readRange() {
  const raw = document.getElementById('a-range')?.value || '';
  const match = /^(\d{4}-\d{2}-\d{2})\s*(?:to|–|-)\s*(\d{4}-\d{2}-\d{2})$/.exec(raw.trim());
  if (!match) return { start: '', end: '' };
  return { start: match[1], end: match[2] };
}

function browserTzOffsetMinutes() {
  return -new Date().getTimezoneOffset();
}

function buildParams() {
  const timelineType = document.getElementById('a-timeline')?.value || 'MONTHLY';
  const { start, end } = readRange();
  const params = { userId: Auth.getUserId(), timelineType, tzOffsetMinutes: browserTzOffsetMinutes() };
  if (start) params.startDate = start + 'T00:00:00';
  if (end) params.endDate = end + 'T23:59:59.999';
  return params;
}

function describeRange() {
  const { start, end } = readRange();
  if (!start && !end) return 'All time';
  if (start && end) return `${formatDate(start + 'T00:00:00')} – ${formatDate(end + 'T00:00:00')}`;
  return start ? `Since ${formatDate(start + 'T00:00:00')}` : `Until ${formatDate(end + 'T00:00:00')}`;
}

function updateResetVisibility() {
  const clear = document.getElementById('a-clear');
  if (!clear) return;
  const differs = (document.getElementById('a-preset')?.value || 'ALL') !== DEFAULT_FILTERS.preset
    || (document.getElementById('a-timeline')?.value || 'MONTHLY') !== DEFAULT_FILTERS.timeline
    || !!document.getElementById('a-range')?.value;
  clear.hidden = !differs;
}

function sum(values) {
  return (values || []).reduce((total, value) => total + (Number(value) || 0), 0);
}

/**
 * Set a KPI figure, falling back to compact notation (₹9.5L) when the full
 * amount cannot fit its column at phone widths. The full figure stays in the
 * tooltip and aria-label.
 */
function setFigureValue(el, value, currency) {
  if (!el) return;
  const full = formatMoney(value, currency);
  el.textContent = full;
  el.title = '';
  el.removeAttribute('aria-label');
  if (el.scrollWidth > el.clientWidth + 1) {
    el.textContent = formatCompact(value, currency);
    el.title = full;
    el.setAttribute('aria-label', full);
  }
}

function renderKpis(income, expense, count, netValue = null) {
  const currency = Auth.getDefaultCurrency();
  const net = netValue != null && Number.isFinite(netValue) ? netValue : income - expense;
  lastKpis = { income, expense, count, net };
  const netEl = document.getElementById('a-net');
  if (netEl) {
    setFigureValue(netEl, net, currency);
    netEl.style.color = net >= 0 ? 'var(--pos)' : 'var(--neg)';
  }
  const incEl = document.getElementById('a-income');
  if (incEl) {
    setFigureValue(incEl, income, currency);
    incEl.style.color = income > 0 ? 'var(--pos)' : 'var(--text)';
  }
  const expEl = document.getElementById('a-expense');
  if (expEl) setFigureValue(expEl, expense, currency);
  const countEl = document.getElementById('a-count');
  if (countEl) countEl.textContent = String(count ?? 0);
  const scopeEl = document.getElementById('a-count-scope');
  if (scopeEl) scopeEl.textContent = describeRange();
}

async function loadAnalytics(userId) {
  const token = ++loadToken;
  charts.forEach(c => destroyChart(c));
  charts = [];

  const params = buildParams();
  const caption = document.getElementById('a-timeline-caption');
  const granularity = params.timelineType.charAt(0) + params.timelineType.slice(1).toLowerCase();
  if (caption) caption.textContent = `${granularity} · ${describeRange()}`;

  const rangeForTotals = {
    startDate: params.startDate ? params.startDate.slice(0, 10) : null,
    endDate: params.endDate ? params.endDate.slice(0, 10) : null,
  };

  const [expResult, incResult, totalsResult] = await Promise.allSettled([
    api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'EXPENSE' }),
    api.get('/analytics/category-pie-chart', { ...params, transactionFilter: 'INCOME' }),
    getTotals(rangeForTotals),
  ]);

  if (token !== loadToken) return; // superseded by a newer request

  const expPie = expResult.status === 'fulfilled' ? expResult.value : null;
  const incPie = incResult.status === 'fulfilled' ? incResult.value : null;

  if (expPie) mountCategoryList('a-expense-wrap', expPie, 'Smaller categories', 'EXPENSE');
  else showError('a-expense-wrap', expResult.reason?.message);

  if (incPie) mountCategoryList('a-income-wrap', incPie, null, 'INCOME');
  else showError('a-income-wrap', incResult.reason?.message);

  // The shared totals selector is the single source of truth, so the KPI strip
  // and the Dashboard agree. Pie sums are only a fallback when it is unavailable.
  const totals = totalsResult.status === 'fulfilled' ? totalsResult.value : null;
  const available = !!totals?.available;
  const incomeTotal = available ? totals.income : sum(incPie?.datasets?.[0]?.data);
  const expenseTotal = available ? totals.expense : sum(expPie?.datasets?.[0]?.data);
  const netTotal = available ? totals.net : incomeTotal - expenseTotal;
  const totalCount = available ? totals.count : 0;
  renderKpis(incomeTotal, expenseTotal, totalCount, netTotal);

  api.get('/analytics/timeline-chart', params)
    .then(timeline => { if (token === loadToken) mountTimeline('a-timeline-wrap', 'a-timeline-chart', timeline, params.timelineType); })
    .catch(err => { if (token === loadToken) showError('a-timeline-wrap', err.message); });
}

function showError(wrapId, message) {
  const wrap = document.getElementById(wrapId);
  if (wrap) wrap.innerHTML = `<p style="color:var(--neg)">${esc(message || 'Could not load this chart.')}</p>`;
}

/**
 * A fixed grid of label / bar / amount / percent. Bar width is the share of the
 * largest category; the percentage is the share of the total.
 */
function mountCategoryList(wrapId, data, foldLabel, kind) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) return;
  const currency = Auth.getDefaultCurrency();
  const labels = data?.labels || [];
  const values = data?.datasets?.[0]?.data || [];

  const rows = labels
    .map((label, i) => ({ label: formatCategory(label), raw: label, value: Number(values[i]) || 0 }))
    .filter(r => r.value > 0)
    .sort((a, b) => b.value - a.value);

  const total = rows.reduce((sum, r) => sum + r.value, 0);
  if (!rows.length || total <= 0) {
    wrap.innerHTML = `<div class="empty-state" style="padding:32px 0"><p>No activity in this period.</p></div>`;
    return;
  }

  const max = rows[0].value;
  const top = rows.slice(0, 6);
  const rest = rows.slice(6);
  // Phones show the first five and reveal the rest through "Show all".
  const mobileExtra = rows.slice(5);
  const isIncome = kind === 'INCOME';

  const pctLabel = (pct) => (pct > 0 && pct < 1 ? '<1%' : `${Math.round(pct)}%`);

  const rowHtml = (r, { folded = false } = {}) => {
    const pct = total > 0 ? (r.value / total) * 100 : 0;
    const width = max > 0 ? Math.max(1, (r.value / max) * 100) : 0;
    return `
      <div class="barlist-row ${folded ? 'is-folded' : ''}" ${folded ? 'data-fold' : `data-category="${esc(r.raw)}"`} role="button" tabindex="0" aria-label="${esc(r.label)}, ${formatMoney(r.value, currency)}, ${pctLabel(pct)} of total">
        <div class="barlist-label">${esc(r.label)}</div>
        <div class="barlist-track"><div class="barlist-fill" style="width:${width.toFixed(1)}%${folded ? '' : `;background:${isIncome ? 'var(--pos)' : 'var(--text-2)'}`}"></div></div>
        <div class="barlist-value">${formatMoney(r.value, currency, { decimals: 0 })}</div>
        <div class="barlist-pct">${pctLabel(pct)}</div>
      </div>`;
  };

  wrap.innerHTML = `
    <div class="barlist">
      ${top.map(r => rowHtml(r)).join('')}
      ${rest.length ? rowHtml({ label: foldLabel || 'Other', value: rest.reduce((s, r) => s + r.value, 0) }, { folded: true }) : ''}
    </div>
    <button type="button" class="barlist-showall" ${rows.length > 5 ? '' : 'hidden'}>Show all ${rows.length} categories</button>
    <div class="barlist-extra" hidden>${mobileExtra.map(r => rowHtml(r)).join('')}</div>
    <div class="barlist-total">Total ${formatMoney(total, currency)}</div>`;

  const moreBtn = wrap.querySelector('.barlist-showall');
  const extra = wrap.querySelector('.barlist-extra');
  if (moreBtn && extra) {
    moreBtn.onclick = () => {
      const open = extra.hidden;
      extra.hidden = !open;
      moreBtn.textContent = open ? 'Show less' : `Show all ${rows.length} categories`;
    };
  }

  wrap.querySelectorAll('[data-category]').forEach(row => {
    const open = () => {
      // Deep-link the ledger at the chosen category.
      sessionStorage.setItem('pendingCategoryFilter', row.dataset.category);
      window.navigateTo('transactions', { query: `?category=${encodeURIComponent(row.dataset.category)}` });
    };
    row.onclick = open;
    row.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } };
  });

  const folded = wrap.querySelector('[data-fold]');
  if (folded) {
    let expanded = false;
    folded.onclick = () => {
      expanded = !expanded;
      const existing = wrap.querySelector('.barlist-sublist');
      if (existing) { existing.remove(); return; }
      const sub = document.createElement('div');
      sub.className = 'barlist-sublist';
      sub.innerHTML = rest.map(r => `
        <div class="barlist-subrow">
          <span>${esc(r.label)}</span>
          <span>${formatMoney(r.value, currency)}</span>
        </div>`).join('');
      folded.insertAdjacentElement('afterend', sub);
    };
    folded.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); folded.click(); } };
  }
}

function fillMonthlyGaps(labels, datasets) {
  const months = labels.map(l => /^\d{4}-\d{2}$/.test(String(l)) ? String(l) : null);
  if (months.some(m => m === null)) return { labels, datasets };

  const parse = (m) => { const [y, mo] = m.split('-').map(Number); return y * 12 + (mo - 1); };
  const format = (n) => `${Math.floor(n / 12)}-${String((n % 12) + 1).padStart(2, '0')}`;
  const points = labels.map((l, i) => ({ idx: parse(l), i })).sort((a, b) => a.idx - b.idx);
  const first = points[0].idx;
  const last = points[points.length - 1].idx;
  if (last - first > 240) return { labels, datasets };

  const denseLabels = [];
  for (let n = first; n <= last; n++) denseLabels.push(format(n));

  const filled = datasets.map(ds => ({
    ...ds,
    data: denseLabels.map(label => {
      const found = points.find(p => format(p.idx) === label);
      return found ? ds.data[found.i] : 0;
    }),
  }));
  return { labels: denseLabels, datasets: filled };
}

function seriesByName(datasets, name) {
  const found = (datasets || []).find(ds => String(ds.label || '').toLowerCase().includes(name));
  return found ? found.data.map(v => Number(v) || 0) : null;
}

function mountTimeline(wrapId, canvasId, timeline) {
  const wrap = document.getElementById(wrapId);
  if (!wrap) return;
  if (!timeline?.labels?.length || !timeline.datasets) {
    wrap.innerHTML = `<div class="empty-state" style="padding:40px 0"><p>No timeline data for this period.</p></div>`;
    return;
  }

  lastTimeline = timeline;
  const timelineType = document.getElementById('a-timeline')?.value || 'MONTHLY';
  const filled = timelineType === 'MONTHLY'
    ? fillMonthlyGaps(timeline.labels, timeline.datasets)
    : { labels: timeline.labels, datasets: timeline.datasets };

  let rawLabels = filled.labels.slice();
  let income = (seriesByName(filled.datasets, 'income') || filled.labels.map(() => 0)).slice();
  let expense = (seriesByName(filled.datasets, 'expense') || filled.labels.map(() => 0)).slice();
  const mobile = isMobileAnalytics();
  let aggregated = false;

  if (mobile && timelineType === 'MONTHLY') {
    // A decade of empty months squeezes the real data into a few pixels.
    const yearly = aggregateMonthlyToYearly(rawLabels, income, expense);
    if (yearly) {
      rawLabels = yearly.labels;
      income = yearly.income;
      expense = yearly.expense;
      aggregated = true;
    }
  }

  if (mobile) {
    const trimmed = trimEmptyBuckets(rawLabels, income, expense);
    rawLabels = trimmed.labels;
    income = trimmed.income;
    expense = trimmed.expense;
  }

  const net = rawLabels.map((_, i) => (income[i] || 0) - (expense[i] || 0));
  const labels = timelineType === 'MONTHLY' && !aggregated ? rawLabels.map(formatMonth) : rawLabels;

  const summaryText = labels.map((label, i) =>
    `${label}: income ${formatMoney(income[i] || 0, Auth.getDefaultCurrency())}, expenses ${formatMoney(expense[i] || 0, Auth.getDefaultCurrency())}, net ${formatMoney(net[i] || 0, Auth.getDefaultCurrency())}`
  ).join('. ');
  wrap.innerHTML = `
    <canvas id="${canvasId}" role="img" aria-describedby="a-timeline-summary" aria-label="Grouped bars of income and expenses over time"></canvas>
    <p class="sr-only" id="a-timeline-summary">${esc(summaryText)}</p>`;
  const chart = createBarChart(document.getElementById(canvasId), {
    labels, income, expense, net,
    currency: Auth.getDefaultCurrency(),
    mobile,
    showIncome: expensesOnly ? false : showIncome,
    showExpense,
    showNet: expensesOnly ? false : showNet,
  });
  // The phone axis is capped at a percentile so one huge month cannot flatten
  // every other bar; say so rather than leaving an unexplained cut.
  if (chart?.$pfaClipped) {
    const note = document.createElement('p');
    note.className = 'chart-clip-note';
    note.textContent = 'Tall bars are clipped so smaller months stay readable.';
    wrap.appendChild(note);
  }
  charts.push(chart);
}

function isMobileAnalytics() {
  try {
    return typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(max-width: 767px)').matches;
  } catch { return false; }
}

/** Over long histories, monthly bars are too dense to read: roll them into years. */
function aggregateMonthlyToYearly(labels, income, expense) {
  if (labels.length <= 36) return null;
  if (!labels.every(l => /^\d{4}-\d{2}$/.test(String(l)))) return null;
  const order = [];
  const byYear = new Map();
  labels.forEach((label, i) => {
    const year = String(label).slice(0, 4);
    if (!byYear.has(year)) { byYear.set(year, { income: 0, expense: 0 }); order.push(year); }
    const bucket = byYear.get(year);
    bucket.income += Number(income[i]) || 0;
    bucket.expense += Number(expense[i]) || 0;
  });
  return {
    labels: order,
    income: order.map(y => byYear.get(y).income),
    expense: order.map(y => byYear.get(y).expense),
  };
}

/** Drop leading and trailing all-zero buckets (keep one neighbour for context). */
function trimEmptyBuckets(labels, income, expense) {
  const hasData = labels.map((_, i) => (Number(income[i]) || 0) !== 0 || (Number(expense[i]) || 0) !== 0);
  let first = hasData.indexOf(true);
  let last = hasData.lastIndexOf(true);
  if (first === -1) {
    first = Math.max(0, labels.length - 12);
    last = labels.length - 1;
  } else {
    first = Math.max(0, first - 1);
    last = Math.min(labels.length - 1, last + 1);
  }
  return {
    labels: labels.slice(first, last + 1),
    income: income.slice(first, last + 1),
    expense: expense.slice(first, last + 1),
  };
}

function wireLegend(userId) {
  const legend = document.getElementById('a-legend');
  if (!legend) return;
  legend.querySelectorAll('[data-series]').forEach(btn => {
    btn.onclick = () => {
      const series = btn.dataset.series;
      if (series === 'expenses-only') {
        expensesOnly = !expensesOnly;
        btn.classList.toggle('is-on', expensesOnly);
        btn.setAttribute('aria-pressed', String(expensesOnly));
        legend.querySelectorAll('[data-series="income"], [data-series="net"]').forEach(b => b.disabled = expensesOnly);
      } else if (series === 'income') {
        showIncome = !showIncome;
        btn.classList.toggle('is-on', showIncome);
        btn.setAttribute('aria-pressed', String(showIncome));
      } else if (series === 'expense') {
        showExpense = !showExpense;
        btn.classList.toggle('is-on', showExpense);
        btn.setAttribute('aria-pressed', String(showExpense));
      } else if (series === 'net') {
        showNet = !showNet;
        btn.classList.toggle('is-on', showNet);
        btn.setAttribute('aria-pressed', String(showNet));
      }
      // Redraw from the last payload without refetching.
      charts.forEach(c => destroyChart(c));
      charts = [];
      if (lastTimeline) mountTimeline('a-timeline-wrap', 'a-timeline-chart', lastTimeline);
      else loadAnalytics(userId);
    };
  });
}
