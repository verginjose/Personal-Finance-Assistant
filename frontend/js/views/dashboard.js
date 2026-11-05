import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  aiPanelHtml, renderAiData, categoryDot,
  formatMoney, formatCategory, formatDayLabel, formatDue, dateTooltip, daysUntilDate, esc,
  segmentedControl, wireSegmented, skeletonRows,
  displayAmountFor, displayName, humanizeNamesIn,
} from '../utils/ui.js';
import {
  PERIODS, periodRange, previousRange, periodHeading, periodScopeLabel,
  comparisonLabel, getTotals,
} from '../utils/totals.js';

let currentPeriod = 'month';
/** Latest totals and top category, so the sentence can update from either. */
let lastTotals = null;
let lastTopCategory = null;

export async function renderDashboard(container) {
  const userId = Auth.getUserId();
  lastTotals = null;
  lastTopCategory = null;

  container.innerHTML = `
    <div class="period-header">
      <h1 id="d-period-label">${esc(periodHeading(currentPeriod))}</h1>
      ${segmentedControl('dashboard-period', PERIODS, currentPeriod, 'Reporting period')}
    </div>

    <section class="statement" id="d-kpis" aria-live="polite">
      <div class="statement-figure">
        <div class="statement-label" id="d-hero-label">Net this month</div>
        <div class="dash-hero-top">
          <span class="hero-number" id="d-net">—</span>
          <span class="dash-delta" id="d-delta"></span>
        </div>
      </div>

      <div class="statement-bars">
        <div class="statement-bar-row">
          <span class="statement-bar-label">Earned</span>
          <span class="statement-bar-track" role="img" aria-label="Income compared with spending">
            <span class="statement-bar-fill is-pos" id="d-earned-bar"></span>
          </span>
          <span class="statement-bar-value" id="d-earned">—</span>
        </div>
        <div class="statement-bar-row">
          <span class="statement-bar-label">Spent</span>
          <span class="statement-bar-track" role="img" aria-label="Spending compared with income">
            <span class="statement-bar-fill is-spent" id="d-spent-bar"></span>
          </span>
          <span class="statement-bar-value" id="d-spent">—</span>
        </div>
      </div>

      <p class="statement-sentence" id="d-sentence"></p>
      <div class="dash-pace" id="d-pace"></div>

      <div class="dash-saved" id="d-saved-wrap" hidden>
        <div class="dash-saved-label"><span id="d-saved-label"></span><span id="d-saved-pct"></span></div>
        <div class="progress-wrap">
          <div class="progress-track" role="progressbar" aria-valuenow="0" aria-valuemin="0" aria-valuemax="100">
            <div class="progress-fill" style="width:0%"></div>
          </div>
        </div>
      </div>
    </section>

    <div class="dash-columns">
      <div class="dash-col dash-col--main">
        <section aria-labelledby="d-recent-title">
          <div class="ledger-head">
            <h2 id="d-recent-title">Recent transactions</h2>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="transactions">View all</button>
          </div>
          <div id="d-recent" aria-live="polite">
            ${skeletonRows(3)}
          </div>
        </section>
      </div>

      <aside class="dash-col dash-col--side">
        ${aiPanelHtml('d')}

        <section class="dash-card" id="d-comingup-section" aria-labelledby="d-comingup-title" hidden>
          <div class="ledger-head">
            <h2 id="d-comingup-title">Coming up</h2>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="subscriptions">View subscriptions</button>
          </div>
          <div id="d-comingup"></div>
          <p class="dash-comingup-foot" id="d-comingup-foot"></p>
        </section>
      </aside>
    </div>`;

  wireSegmented(container, (period) => {
    currentPeriod = period;
    const label = document.getElementById('d-period-label');
    if (label) label.textContent = periodHeading(currentPeriod);
    lastTotals = null;
    lastTopCategory = null;
    loadSummary();
    loadComposition();
    loadInsights();
  });

  container.querySelectorAll('[data-nav]').forEach(btn => {
    btn.onclick = () => window.navigateTo(btn.dataset.nav);
  });

  const loadSummary = () => {
    const range = periodRange(currentPeriod);
    const prev = previousRange(currentPeriod);

    getTotals({ ...range, force: true })
      .then(summary => {
        if (!document.getElementById('d-net')) return;
        if (!prev) { renderFigures(summary, null); return; }
        getTotals({ ...prev, force: true })
          .then(previous => renderFigures(summary, previous))
          .catch(() => renderFigures(summary, null));
      })
      .catch(() => renderFigures(null, null));

    // Five rows fill the ledger column without turning the page into a table.
    api.get('/upsert/entries', { userId, page: 0, size: 5, ...range })
      .then(renderRecent)
      .catch(() => renderRecent(null));
  };

  // The top expense category lets the statement say "most of it went to X"
  // without guessing. It is decoration on top of the totals, never a blocker.
  const loadComposition = () => {
    const range = periodRange(currentPeriod);
    api.get('/analytics/category-pie-chart', {
      userId,
      ...range,
      timelineType: 'MONTHLY',
      tzOffsetMinutes: -new Date().getTimezoneOffset(),
      transactionFilter: 'EXPENSE',
    }).then((pie) => {
      const labels = pie?.labels || [];
      const values = pie?.datasets?.[0]?.data || [];
      let best = null;
      labels.forEach((label, i) => {
        const value = Number(values[i]) || 0;
        if (!best || value > best.value) best = { label, value };
      });
      lastTopCategory = best && best.value > 0 ? best.label : null;
      updateSentence();
    }).catch(() => { /* the sentence simply omits the category */ });
  };

  loadSummary();
  loadComposition();

  // Notes can take seconds; never block the rest of the page on them.
  const loadInsights = () => {
    const range = periodRange(currentPeriod);
    api.get('/analytics/ai-insights', { userId, ...range })
      .then((ai) => {
        if (document.getElementById('d-ai-insights-list')) renderAiData('d', ai, refreshAdvisor, { scopeLabel: periodScopeLabel(currentPeriod) });
      })
      .catch((e) => {
        if (document.getElementById('d-ai-insights-list')) renderAiData('d', { error: e.message }, refreshAdvisor, { scopeLabel: periodScopeLabel(currentPeriod) });
      });
  };
  loadInsights();

  try {
    const subscriptions = await api.get('/upsert/subscriptions', {
      userId,
      tzOffsetMinutes: -new Date().getTimezoneOffset(),
    }).catch(() => []);
    if (!document.getElementById('d-comingup')) return; // navigated away
    renderComingUp(subscriptions);
  } catch (err) {
    toast('Failed to load dashboard: ' + err.message, 'error');
  }
}

function renderFigures(totals, previous) {
  const wrap = document.getElementById('d-kpis');
  if (!wrap) return;

  const currency = totals?.currency || Auth.getDefaultCurrency();
  const income = Number(totals?.income) || 0;
  const expense = Number(totals?.expense) || 0;
  const net = Number(totals?.net) || 0;
  const noIncome = income <= 0;

  lastTotals = { totals, previous, currency, income, expense, net, noIncome };

  const labelEl = document.getElementById('d-hero-label');
  const netValue = document.getElementById('d-net');
  const earnedEl = document.getElementById('d-earned');
  const spentEl = document.getElementById('d-spent');
  const earnedBar = document.getElementById('d-earned-bar');
  const spentBar = document.getElementById('d-spent-bar');

  // The hero shows the figure that means something for the period. Early in a
  // month, before income lands, a negative net is normal, not an alarm.
  if (labelEl) labelEl.textContent = noIncome ? 'Spent this period' : 'Net this period';
  if (netValue) {
    const value = noIncome ? expense : net;
    netValue.textContent = formatMoney(value, currency);
    netValue.className = `hero-number ${noIncome ? 'is-neg' : net >= 0 ? 'is-pos' : 'is-neg'}`;
  }
  if (earnedEl) earnedEl.textContent = formatMoney(income, currency);
  if (spentEl) spentEl.textContent = formatMoney(expense, currency);

  // One scale for both bars, so the comparison is honest.
  const scale = Math.max(income, expense, 1);
  if (earnedBar) earnedBar.style.width = `${Math.min(100, (income / scale) * 100)}%`;
  if (spentBar) spentBar.style.width = `${Math.min(100, (expense / scale) * 100)}%`;

  // Like-for-like comparison: the same days of the previous month, short of
  // comparing four days of spending against a whole month.
  const delta = document.getElementById('d-delta');
  const pace = document.getElementById('d-pace');
  if (delta) {
    delta.className = 'dash-delta';
    if (!previous || !previous.count) {
      delta.textContent = '';
    } else {
      const diff = expense - (Number(previous.expense) || 0);
      const label = comparisonLabel(currentPeriod);
      if (Math.abs(diff) < 0.005) delta.textContent = `Spending is unchanged vs ${label}`;
      else delta.textContent = `${formatMoney(Math.abs(diff), currency)} ${diff > 0 ? 'more' : 'less'} than ${label}`;
    }
  }
  if (pace) {
    pace.textContent = '';
    if (currentPeriod === 'month') {
      const now = new Date();
      const daysElapsed = now.getDate();
      const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
      if (daysElapsed >= 7) {
        const paceValue = (expense / daysElapsed) * daysInMonth;
        pace.textContent = `At this pace, ${formatMoney(paceValue, currency)} by the end of ${now.toLocaleDateString('en-IN', { month: 'long' })}.`;
      }
    }
  }

  const savedWrap = document.getElementById('d-saved-wrap');
  if (savedWrap) {
    // Only show the kept-income bar when there is something kept; the
    // statement sentence already covers a negative month.
    savedWrap.hidden = !(income > 0 && net > 0);
    if (income > 0 && net > 0) {
      const savedRate = Math.max(0, (net / income) * 100);
      const label = document.getElementById('d-saved-label');
      const pct = document.getElementById('d-saved-pct');
      if (label) {
        label.textContent = net > 0
          ? `Kept ${savedRate.toFixed(0)}% of income`
          : 'Nothing saved this period.';
      }
      if (pct) pct.textContent = net > 0 ? formatMoney(net, currency) : '';
      const fill = savedWrap.querySelector('.progress-fill');
      const track = savedWrap.querySelector('.progress-track');
      if (fill) fill.style.background = savedRate >= 20 ? 'var(--pos)' : 'var(--warn)';
      if (track) {
        track.setAttribute('aria-valuenow', savedRate.toFixed(0));
        if (fill) fill.style.width = `${Math.min(savedRate, 100)}%`;
      }
    }
  }

  updateSentence();
}

/** One plain sentence that answers "am I okay?" from the real numbers. */
function updateSentence() {
  const el = document.getElementById('d-sentence');
  if (!el || !lastTotals) return;
  const { currency, income, expense, net } = lastTotals;
  const top = lastTopCategory
    ? (String(lastTopCategory).toUpperCase() === 'OTHERS'
      ? 'uncategorized spending'
      : formatCategory(lastTopCategory).toLowerCase())
    : '';

  if (expense <= 0) {
    el.textContent = income > 0
      ? `You earned ${formatMoney(income, currency)} and have not spent any of it yet.`
      : 'Nothing recorded for this period yet. Add a transaction to start the ledger.';
    return;
  }

  if (income <= 0) {
    el.textContent = `You have spent ${formatMoney(expense, currency)} this period. No income is recorded yet.`;
    return;
  }

  if (net < 0) {
    const over = formatMoney(Math.abs(net), currency);
    el.innerHTML = `You spent <strong>${esc(over)}</strong> more than you earned this period.`
      + (top ? ` Most of it went to ${esc(top.toLowerCase())}.` : '');
    return;
  }

  const saved = (net / income) * 100;
  el.innerHTML = `You kept <strong class="is-pos">${esc(formatMoney(net, currency))}</strong> of the `
    + `${esc(formatMoney(income, currency))} you earned this period — ${saved.toFixed(0)}% of your income.`
    + (top ? ` The largest expense was ${esc(top.toLowerCase())}.` : '');
}

function settlementTitle(t, userId) {
  const cat = String(t.expenseCategory || t.incomeCategory || t.category || '').toUpperCase();
  if (cat !== 'SETTLEMENT') return humanizeNamesIn(t.name || 'Transaction');
  const raw = String(t.name || t.description || '').trim();
  let m = /^paid\s+(.+)$/i.exec(raw);
  if (m) return `You paid ${displayName(m[1])}`;
  m = /^received from\s+(.+)$/i.exec(raw);
  if (m) return `${displayName(m[1])} paid you`;
  m = /^payment from\s+(.+?)\s+to\s+(.+)$/i.exec(raw);
  if (m) {
    const from = displayName(m[1]);
    const to = displayName(m[2]);
    return from === displayName(Auth.getName() || Auth.getEmail() || '') ? `You paid ${to}` : `${from} paid ${to}`;
  }
  return 'Settlement recorded';
}

/** Group name from a settlement note ("Settlement · Office lunch crew"). */
function settlementGroup(t) {
  const m = /^settlement\s*[·:-]\s*(.+)$/i.exec(String(t.description || ''));
  return m ? m[1].trim() : '';
}

function renderRecent(result) {
  const wrap = document.getElementById('d-recent');
  if (!wrap) return;
  const items = result?.content || [];
  const currency = Auth.getDefaultCurrency();

  if (!items.length) {
    wrap.innerHTML = `<p class="meta" style="padding:20px 0">No transactions in this period. <button type="button" class="insight-action" id="d-empty-add">Add one</button></p>`;
    document.getElementById('d-empty-add')?.addEventListener('click', () => window.openAddTransaction?.());
    return;
  }

  const groups = new Map();
  for (const t of items) {
    const key = (t.createdAt || '').slice(0, 10);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(t);
  }

  wrap.innerHTML = [...groups.entries()].map(([key, rows]) => {
    const total = rows.reduce((sum, t) => {
      const shown = displayAmountFor(t, currency);
      if (shown.currency !== currency) return sum;
      return sum + (t.type === 'INCOME' ? Number(shown.amount) : -Number(shown.amount));
    }, 0);
    const rowsHtml = rows.map(t => {
      const isIncome = t.type === 'INCOME';
      const cat = t.expenseCategory || t.incomeCategory || t.category || '';
      const shown = displayAmountFor(t, currency);
      const amount = formatMoney(isIncome ? shown.amount : -shown.amount, shown.currency);
      const tags = [
        t.recurring ? `<span class="txn-tag">${icon('repeat', 'xs')} ${esc(titleCase(t.recurringPeriod || 'Recurring'))}</span>` : '',
      ].join('');
      const receipt = t.receiptUrl
        ? `<button type="button" class="attach-btn" data-receipt="${esc(t.receiptUrl)}" aria-label="Receipt attached" title="Receipt attached">${icon('paperclip', 'sm')}</button>`
        : '';
      const isSettlement = String(cat).toUpperCase() === 'SETTLEMENT';
      const marker = isSettlement
        ? `<span class="row-icon">${icon('arrow-right', 'sm')}</span>`
        : categoryDot(cat);
      return `
        <div class="ledger-row" role="button" tabindex="0" data-txn="${esc(String(t.id || ''))}">
          ${marker}
          <div class="ledger-name">
            ${esc(settlementTitle(t))}
            <div class="txn-sub">${esc(isSettlement ? (settlementGroup(t) || formatCategory(cat)) : formatCategory(cat))}${shown.secondary ? ` · ${esc(shown.secondary)}` : ''}${tags}</div>
          </div>
          ${receipt}
          <span class="ledger-amount ${isIncome ? 'is-pos' : ''}">${amount}</span>
        </div>`;
    }).join('');

    return `
      <div class="ledger-day"><span title="${esc(dateTooltip(`${key}T00:00:00`))}">${esc(formatDayLabel(key))}</span><span class="day-total">${formatMoney(total, currency)}</span></div>
      ${rowsHtml}`;
  }).join('');

  wrap.querySelectorAll('.ledger-row').forEach(row => {
    row.onclick = () => window.navigateTo('transactions');
    row.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); window.navigateTo('transactions'); }
    };
  });
  wrap.querySelectorAll('[data-receipt]').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      window.openReceiptLightbox(btn.dataset.receipt);
    };
  });
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

/* ── Coming up ───────────────────────────────────────────────────────────── */
function renderComingUp(subs) {
  const section = document.getElementById('d-comingup-section');
  const list = document.getElementById('d-comingup');
  const foot = document.getElementById('d-comingup-foot');
  if (!section || !list || !foot) return;

  const charges = (subs || []).filter(s => s.nextChargeDate);
  if (!charges.length) { section.hidden = true; return; }

  // Derive the countdown from the date; daysUntilCharge is a nightly snapshot
  // and reads as "tomorrow" for days after the job stopped advancing a row.
  const withDays = charges.map(s => ({ sub: s, days: daysUntilDate(s.nextChargeDate) }));
  const due = withDays
    .filter(x => x.days != null && x.days >= 0 && x.days <= 7)
    .sort((a, b) => a.days - b.days)
    .map(x => x.sub);
  const shown = due.length ? due : [...charges]
    .sort((a, b) => new Date(a.nextChargeDate) - new Date(b.nextChargeDate))
    .slice(0, 3);

  const currency = Auth.getDefaultCurrency();
  list.innerHTML = shown.map(s => `
    <div class="comingup-row">
      <div class="comingup-main">
        <div class="comingup-name">${esc(s.name)}</div>
        <div class="comingup-when" title="${esc(dateTooltip(s.nextChargeDate))}">${esc(formatDue(s.nextChargeDate, daysUntilDate(s.nextChargeDate)))}</div>
      </div>
      <span class="comingup-amount">${formatMoney(Number(s.amount) || 0, s.currency || currency)}</span>
    </div>`).join('');

  if (due.length) {
    const total = due.reduce((sum, s) => sum + (Number(s.amount) || 0), 0);
    foot.textContent = `${formatMoney(total, currency)} due this week`;
  } else {
    foot.textContent = 'No charges in the next 7 days.';
  }
  section.hidden = false;
}

async function refreshAdvisor() {
  const btn = document.getElementById('d-ai-refresh');
  if (btn) btn.disabled = true;
  try {
    const range = periodRange(currentPeriod);
    const ai = await api.get('/analytics/ai-insights', { userId: Auth.getUserId(), refresh: true, ...range });
    renderAiData('d', ai, refreshAdvisor, { scopeLabel: periodScopeLabel(currentPeriod) });
    toast('Notes refreshed', 'success');
  } catch (err) {
    renderAiData('d', { error: err.message }, refreshAdvisor, { scopeLabel: periodScopeLabel(currentPeriod) });
    toast('Could not refresh notes: ' + err.message, 'error');
  } finally {
    const current = document.getElementById('d-ai-refresh');
    if (current) current.disabled = false;
  }
}
