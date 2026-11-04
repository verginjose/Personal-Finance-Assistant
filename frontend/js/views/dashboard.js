import { api, Auth, toast } from '../utils/api.js';
import { icon, categoryIcon } from '../utils/icons.js';
import {
  healthPanelHtml, aiPanelHtml, renderHealthData, renderAiData,
  formatMoney, formatCategory, formatDayLabel, formatDue, dateTooltip, esc,
  segmentedControl, wireSegmented, progressBar, skeletonRows,
  displayAmountFor, displayName, openModal, humanizeNamesIn,
} from '../utils/ui.js';
import {
  PERIODS, periodRange, previousRange, periodHeading,
  comparisonLabel, getTotals,
} from '../utils/totals.js';

let currentPeriod = 'month';

export async function renderDashboard(container) {
  const userId = Auth.getUserId();

  container.innerHTML = `
    <div class="period-header">
      <h1 id="d-period-label">${esc(periodHeading(currentPeriod))}</h1>
      ${segmentedControl('dashboard-period', PERIODS, currentPeriod, 'Reporting period')}
    </div>

    <section class="dash-hero" id="d-kpis" aria-live="polite">
      <div class="dash-hero-label" id="d-hero-label">Net</div>
      <div class="dash-hero-top">
        <span class="hero-number" id="d-net">—</span>
        <span class="dash-delta" id="d-delta"></span>
      </div>
      <div class="dash-sub">
        <span class="dash-sub-item" id="d-earned-item">Earned <strong id="d-earned">—</strong></span>
        <span class="dash-sub-item" id="d-spent-item">Spent <strong id="d-spent">—</strong></span>
        <span class="dash-sub-item" id="d-net-item" hidden>Net <strong id="d-net-sub">—</strong></span>
      </div>
      <div class="dash-pace" id="d-pace"></div>
      <div class="dash-saved" id="d-saved-wrap" hidden>
        <div class="dash-saved-label"><span id="d-saved-label"></span><span id="d-saved-pct"></span></div>
        ${progressBar(0, 'var(--pos)')}
      </div>
    </section>

    <div class="dash-columns">
      <div class="dash-col dash-col--main">
        <section aria-labelledby="d-recent-title">
          <div class="ledger-head">
            <h2 id="d-recent-title" class="section-title">Recent transactions</h2>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="transactions">View all</button>
          </div>
          <div id="d-recent" aria-live="polite">
            ${skeletonRows(6)}
          </div>
        </section>

        <section class="dash-card" id="d-comingup-section" aria-labelledby="d-comingup-title" hidden>
          <div class="ledger-head">
            <h2 id="d-comingup-title" class="section-title">Coming up</h2>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="subscriptions">View subscriptions</button>
          </div>
          <div id="d-comingup"></div>
          <p class="dash-comingup-foot" id="d-comingup-foot"></p>
        </section>
      </div>

      <aside class="dash-col dash-col--side">
        <section class="dash-card" id="d-goals-summary" aria-labelledby="d-goals-title">
          <div class="ledger-head">
            <h2 class="section-title" id="d-goals-title">Goals</h2>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="goals">View all</button>
          </div>
          <div id="d-goals-rows">
            <div class="skeleton skeleton-line" style="width:60%"></div>
            <div class="skeleton skeleton-line" style="width:100%;margin-top:10px"></div>
            <div class="skeleton skeleton-line" style="width:45%;margin-top:10px"></div>
          </div>
        </section>

        <section class="dash-card" id="d-budgets-summary" aria-labelledby="d-budgets-title" hidden>
          <div class="ledger-head">
            <h2 class="section-title" id="d-budgets-title">Budgets</h2>
            <button type="button" class="btn btn-ghost btn-sm" data-nav="goals">View all</button>
          </div>
          <div id="d-budget-rows" class="budget-mini-list"></div>
        </section>

        ${aiPanelHtml('d')}
        ${healthPanelHtml('d')}
      </aside>
    </div>`;

  wireSegmented(container, (period) => {
    currentPeriod = period;
    const label = document.getElementById('d-period-label');
    if (label) label.textContent = periodHeading(currentPeriod);
    loadSummary();
    renderPendingInsights();
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

    api.get('/upsert/entries', { userId, page: 0, size: 6, ...range })
      .then(renderRecent)
      .catch(() => renderRecent(null));
  };

  loadSummary();

  // AI insights can take seconds; never block the rest of the page on it.
  const loadInsights = () => {
    api.get('/analytics/ai-insights', { userId })
      .then((ai) => {
        if (document.getElementById('d-ai-insights-list')) renderAiData('d', ai, refreshAdvisor, { scopeLabel: 'all time' });
      })
      .catch((e) => {
        if (document.getElementById('d-ai-insights-list')) renderAiData('d', { error: e.message }, refreshAdvisor, { scopeLabel: 'all time' });
      });
  };
  const renderPendingInsights = () => {
    const meta = document.getElementById('d-ai-meta');
    const summary = document.getElementById('d-ai-summary');
    if (summary) summary.textContent = 'Based on all time';
    if (meta) meta.textContent = '';
  };
  loadInsights();

  const subscriptionsPromise = api.get('/upsert/subscriptions', { userId }).catch(() => []);

  try {
    const [health, budgets, goals, subscriptions] = await Promise.all([
      api.get('/analytics/health-score', { userId }).catch(() => null),
      api.get('/upsert/budgets', { userId }).catch(() => []),
      api.get('/upsert/goals', { userId }).catch(() => []),
      subscriptionsPromise,
    ]);

    if (!document.getElementById('d-health-score')) return; // navigated away

    renderBudgets(budgets);
    renderComingUp(subscriptions);
    const capped = await renderGoalsSummary(goals, userId);
    // The score endpoint is all-time; label it rather than pretend it follows
    // the period control.
    // TODO(backend): return a period-aware health score and insights so both
    // follow the segmented control.
    renderHealthData('d', health, { capLabel: capped, scopeLabel: 'all time' });
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

  const labelEl = document.getElementById('d-hero-label');
  const netValue = document.getElementById('d-net');
  const earnedEl = document.getElementById('d-earned');
  const spentEl = document.getElementById('d-spent');
  const netSub = document.getElementById('d-net-sub');
  const earnedItem = document.getElementById('d-earned-item');
  const spentItem = document.getElementById('d-spent-item');
  const netItem = document.getElementById('d-net-item');

  // The hero shows the figure that means something for the period. Early in a
  // month, before income lands, a negative net is normal, not an alarm.
  if (labelEl) labelEl.textContent = noIncome ? 'Spent so far' : 'Net';
  if (netValue) {
    const value = noIncome ? expense : net;
    netValue.textContent = formatMoney(value, currency);
    netValue.className = `hero-number ${!noIncome && net > 0 ? 'is-pos' : ''}`;
  }
  if (earnedEl) earnedEl.textContent = formatMoney(income, currency);
  if (spentEl) spentEl.textContent = formatMoney(expense, currency);
  if (netSub) netSub.textContent = formatMoney(net, currency);
  if (earnedItem) earnedItem.hidden = false;
  if (spentItem) spentItem.hidden = noIncome;
  if (netItem) netItem.hidden = !noIncome;

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
        pace.textContent = `On pace for ${formatMoney(paceValue, currency)} this month`;
      }
    }
  }

  const savedWrap = document.getElementById('d-saved-wrap');
  if (savedWrap) {
    savedWrap.hidden = !(income > 0);
    if (income > 0) {
      const savedRate = Math.max(0, (net / income) * 100);
      const label = document.getElementById('d-saved-label');
      const pct = document.getElementById('d-saved-pct');
      if (label) label.textContent = `Saved ${savedRate.toFixed(0)}% of income`;
      if (pct) pct.textContent = formatMoney(Math.max(net, 0), currency);
      const fill = savedWrap.querySelector('.progress-fill');
      const track = savedWrap.querySelector('.progress-track');
      if (fill) fill.style.background = savedRate >= 20 ? 'var(--pos)' : 'var(--warn)';
      if (track) {
        track.setAttribute('aria-valuenow', savedRate.toFixed(0));
        if (fill) fill.style.width = `${Math.min(savedRate, 100)}%`;
      }
    }
  }
}

function settlementTitle(t, userId) {
  const cat = String(t.expenseCategory || t.incomeCategory || t.category || '').toUpperCase();
  if (cat !== 'SETTLEMENT') return humanizeNamesIn(t.name || 'Transaction');
  const raw = String(t.name || t.description || '').trim();
  let m = /^paid\s+(.+)$/i.exec(raw);
  if (m) return `You paid ${displayName(m[1])}`;
  m = /^received from\s+(.+)$/i.exec(raw);
  if (m) return `${displayName(m[1])} paid you`;
  m = /payment from\s+(.+?)\s+to\s+(.+)$/i.exec(raw);
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
    wrap.innerHTML = `<p class="meta" style="padding:20px 0">No transactions in this period. Add one to get started.</p>`;
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
      return `
        <div class="ledger-row" role="button" tabindex="0" data-txn="${esc(String(t.id || ''))}">
          <span class="dash-recent-icon">${icon(isSettlement ? 'arrow-right' : categoryIcon(cat), 'sm')}</span>
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

/* ── Budgets (one-line rows) ─────────────────────────────────────────────── */
function renderBudgets(budgets) {
  const section = document.getElementById('d-budgets-summary');
  const list = document.getElementById('d-budget-rows');
  if (!section || !list) return;
  const rows = (budgets || []).slice(0, 3);
  if (!rows.length) { section.hidden = true; return; }

  list.innerHTML = rows.map(b => {
    const pct = Math.min(Number(b.utilizationPercentage) || 0, 100);
    const currency = b.currency || Auth.getDefaultCurrency();
    const color = pct >= 100 ? 'var(--neg)' : pct >= 80 ? 'var(--warn)' : 'var(--pos)';
    return `
      <div class="budget-mini">
        <div class="budget-mini-head">
          <span class="budget-mini-name">${esc(formatCategory(b.expenseCategory))}</span>
          <span class="budget-mini-figures">${formatMoney(Number(b.spentAmount) || 0, currency)} of ${formatMoney(Number(b.budgetAmount) || 0, currency)}</span>
        </div>
        ${progressBar(pct, color)}
      </div>`;
  }).join('');
  section.hidden = false;
}

/* ── Coming up ───────────────────────────────────────────────────────────── */
function renderComingUp(subs) {
  const section = document.getElementById('d-comingup-section');
  const list = document.getElementById('d-comingup');
  const foot = document.getElementById('d-comingup-foot');
  if (!section || !list || !foot) return;

  const charges = (subs || []).filter(s => s.nextChargeDate);
  if (!charges.length) { section.hidden = true; return; }

  const due = charges
    .filter(s => s.daysUntilCharge != null && s.daysUntilCharge >= 0 && s.daysUntilCharge <= 7)
    .sort((a, b) => a.daysUntilCharge - b.daysUntilCharge);
  const shown = due.length ? due : [...charges]
    .sort((a, b) => new Date(a.nextChargeDate) - new Date(b.nextChargeDate))
    .slice(0, 3);

  const currency = Auth.getDefaultCurrency();
  list.innerHTML = shown.map(s => `
    <div class="comingup-row">
      <span class="row-icon">${icon('repeat', 'sm')}</span>
      <div class="comingup-main">
        <div class="comingup-name">${esc(s.name)}</div>
        <div class="comingup-when" title="${esc(dateTooltip(s.nextChargeDate))}">${esc(formatDue(s.nextChargeDate, s.daysUntilCharge))}</div>
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

/**
 * Goal cards with a mini progress bar and a status chip. Returns the label of
 * the first goal that is behind, which the health panel uses to withhold an
 * inconsistent top grade.
 */
async function renderGoalsSummary(goals, userId) {
  const active = (goals || []).filter(g => !g.completed);
  const rowsEl = document.getElementById('d-goals-rows');
  const section = document.getElementById('d-goals-summary');
  if (!rowsEl || !section) return '';

  if (!active.length) {
    rowsEl.innerHTML = `
      <div class="dash-empty">
        <p>Set a goal to start saving toward something specific.</p>
        <button type="button" class="btn btn-secondary btn-sm" data-nav="goals">New goal</button>
      </div>`;
    rowsEl.querySelector('[data-nav]')?.addEventListener('click', () => window.navigateTo('goals'));
    return '';
  }

  const shown = active.slice(0, 2);
  const forecasts = await Promise.allSettled(
    shown.map(g => g.deadline
      ? api.get(`/analytics/goals/${g.id}/forecast`, { userId }).catch(() => null)
      : Promise.resolve(null))
  );

  let capped = '';
  rowsEl.innerHTML = shown.map((g, i) => {
    const pct = Math.min(Number(g.progressPercentage) || 0, 100);
    const months = monthsUntil(g.deadline);
    const remaining = Math.max(0, Number(g.targetAmount) - Number(g.savedAmount));
    const required = months ? remaining / months : null;
    const forecast = forecasts[i]?.status === 'fulfilled' ? forecasts[i].value : null;
    const actual = forecast ? Number(forecast.monthlyVelocity) || 0 : null;
    const expected = goalExpectedPct(g);
    const behind = required != null && actual != null ? actual < required : false;
    if (behind && !capped) capped = `Goal behind: ${g.name}`;
    const chipHtml = behind ? '<span class="chip chip--warn">Behind</span>' : '<span class="chip chip--pos">On track</span>';
    return `
      <article class="goal-mini">
        <div class="goal-mini-head">
          <span class="goal-mini-name">${esc(g.name)}</span>
          ${chipHtml}
        </div>
        ${progressBar(pct, behind ? 'var(--warn)' : 'var(--pos)', false, { expected })}
        <div class="goal-mini-figures">
          <span><strong>${formatMoney(Number(g.savedAmount) || 0, g.currency)}</strong> of ${formatMoney(Number(g.targetAmount) || 0, g.currency)}</span>
          <span>${pct.toFixed(0)}%</span>
        </div>
        ${required != null ? `<div class="goal-reco"><span>Save <strong>${formatMoney(required, g.currency)}</strong> a month to reach it by ${esc(formatMonthDate(g.deadline))}</span></div>` : ''}
        <button type="button" class="btn btn-secondary btn-sm goal-mini-add" data-goal="${esc(String(g.id))}">Add money</button>
      </article>`;
  }).join('');

  rowsEl.querySelectorAll('[data-goal]').forEach(btn => {
    btn.onclick = () => {
      const goal = shown.find(g => String(g.id) === btn.dataset.goal);
      if (goal) openGoalContribution(goal, userId);
    };
  });
  return capped;
}

function goalExpectedPct(g) {
  if (!g.deadline || !g.createdAt) return null;
  const start = new Date(g.createdAt).getTime();
  const end = new Date(g.deadline).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return Math.min(Math.max(((Date.now() - start) / (end - start)) * 100, 0), 100);
}

function formatMonthDate(value) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

function openGoalContribution(goal, userId) {
  openModal(`Add money to ${displayName(goal.name)}`, `
    <form id="goal-money-form">
      <p class="modal-lead">Every contribution is recorded as a transfer into <strong style="color:var(--text)">${esc(goal.name)}</strong>.</p>
      <div class="form-group">
        <label for="gm-amount">Amount</label>
        <input class="form-input" id="gm-amount" type="text" inputmode="decimal" required placeholder="0.00">
      </div>
      <div class="modal-actions">
        <button type="button" class="btn btn-secondary" data-modal-cancel>Cancel</button>
        <button type="submit" class="btn btn-primary">Add money</button>
      </div>
    </form>`, {
    onSubmit: async () => {
      const amount = parseFloat(document.getElementById('gm-amount').value);
      if (!Number.isFinite(amount) || amount <= 0) throw new Error('Enter an amount greater than zero');
      await api.patch(`/upsert/goals/${goal.id}/contribute`, { amount }, { userId });
      toast('Money added', 'success');
      const goals = await api.get('/upsert/goals', { userId }).catch(() => []);
      await renderGoalsSummary(goals, userId);
    },
  });
}

function monthsUntil(deadline) {
  const end = new Date(deadline);
  if (Number.isNaN(end.getTime())) return null;
  const now = new Date();
  const months = (end.getFullYear() - now.getFullYear()) * 12 + (end.getMonth() - now.getMonth());
  return Math.max(1, months);
}

async function refreshAdvisor() {
  const btn = document.getElementById('d-ai-refresh');
  if (btn) btn.disabled = true;
  try {
    const ai = await api.get('/analytics/ai-insights', { userId: Auth.getUserId(), refresh: true });
    renderAiData('d', ai, refreshAdvisor, { scopeLabel: 'all time' });
    toast('Insights refreshed', 'success');
  } catch (err) {
    renderAiData('d', { error: err.message }, refreshAdvisor, { scopeLabel: 'all time' });
    toast('Could not refresh insights: ' + err.message, 'error');
  } finally {
    const current = document.getElementById('d-ai-refresh');
    if (current) current.disabled = false;
  }
}
