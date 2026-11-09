import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, escAttr, pageHeader, emptyState, formatMoney, formatCategory, formatDate,
  progressBar, goalRuler, budgetStatusColor, openModal, confirmModal, modalActions,
  EXPENSE_CATS, categoryOptions, currencyOptionHtml,
} from '../utils/ui.js';

export async function renderGoals(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Goals and budgets', '')}
    <div class="goals-layout">
      <div class="goals-main">
        <section aria-labelledby="goals-title">
          <div class="section-header">
            <h2 id="goals-title">Savings goals</h2>
            <button class="btn btn-ghost btn-sm" id="add-goal-btn">${icon('plus', 'sm')} New goal</button>
          </div>
          <div id="goals-list" class="goal-list"><div class="spinner-center"><span class="spinner"></span></div></div>
        </section>
        <section aria-labelledby="budgets-title">
          <div class="section-header">
            <h2 id="budgets-title">Category budgets</h2>
            <button class="btn btn-ghost btn-sm" id="add-budget-btn">${icon('plus', 'sm')} New budget</button>
          </div>
          <div id="budgets-list" class="goal-list"><div class="spinner-center"><span class="spinner"></span></div></div>
        </section>
      </div>
      <aside class="goals-side" id="goals-side" aria-live="polite"></aside>
    </div>`;

  bindEvents(userId);
  await Promise.all([loadGoals(userId), loadBudgets(userId)]);
}

async function loadGoals(uid) {
  const el = document.getElementById('goals-list');
  try {
    const goals = await api.get('/upsert/goals', { userId: uid });
    const active = goals.filter(g => !g.completed);
    const forecasts = await Promise.allSettled(
      active.map(g => g.deadline
        ? api.get(`/analytics/goals/${g.id}/forecast`, { userId: uid }).catch(() => null)
        : Promise.resolve(null))
    );
    const forecastById = new Map();
    active.forEach((g, i) => {
      if (forecasts[i]?.status === 'fulfilled') forecastById.set(g.id, forecasts[i].value);
    });

    renderPlan(active);
    if (!goals.length) {
      el.className = 'goal-list';
      el.innerHTML = emptyState('target', 'No savings goals',
        'Set a goal to start saving toward something specific.',
        '<button class="btn btn-primary" id="empty-new-goal">New goal</button>');
      document.getElementById('empty-new-goal').onclick = () => document.getElementById('add-goal-btn').click();
      return;
    }

    el.className = `goal-list ${goals.length <= 2 ? 'is-grid' : ''}`;
    el.innerHTML = goals.map(g => goalCard(g, forecastById.get(g.id))).join('');

    goals.forEach(g => {
      bindOverflow(`goal-menu-${g.id}`, `goal-actions-${g.id}`);
      document.getElementById(`del-goal-${g.id}`)?.addEventListener('click', () => deleteGoal(g.id, uid, document.getElementById(`goal-card-${g.id}`)));
      document.getElementById(`view-goal-${g.id}`)?.addEventListener('click', () => viewGoalDetails(g, uid));
      document.getElementById(`add-money-${g.id}`)?.addEventListener('click', () => addContribution(g, uid));
    });
  } catch (e) { toast(e.message, 'error'); }
}

function bindOverflow(menuId, actionsId) {
  const btn = document.getElementById(menuId);
  const menu = document.getElementById(actionsId);
  if (!btn || !menu) return;
  btn.onclick = (e) => {
    e.stopPropagation();
    document.querySelectorAll('.overflow-menu').forEach(m => { if (m !== menu) m.hidden = true; });
    menu.hidden = !menu.hidden;
  };
}

async function loadBudgets(uid) {
  const el = document.getElementById('budgets-list');
  try {
    const budgets = await api.get('/upsert/budgets', { userId: uid });
    if (!budgets.length) {
      el.className = 'goal-list';
      el.innerHTML = emptyState('bar-chart', 'No budgets set',
        'A category budget tells you before a month gets away from you.',
        '<button class="btn btn-primary" id="empty-new-budget">New budget</button>');
      document.getElementById('empty-new-budget').onclick = () => document.getElementById('add-budget-btn').click();
      return;
    }
    el.className = `goal-list ${budgets.length <= 2 ? 'is-grid' : ''}`;
    el.innerHTML = budgets.map(budgetCard).join('');
    budgets.forEach(b => {
      bindOverflow(`budget-menu-${b.budgetId}`, `budget-actions-${b.budgetId}`);
      document.getElementById(`del-budget-${b.budgetId}`)?.addEventListener('click', (e) => deleteBudget(b.budgetId, uid, document.getElementById(`budget-card-${b.budgetId}`)));
    });
  } catch (e) { toast(e.message, 'error'); }
}

function monthsUntil(deadline) {
  const end = new Date(deadline);
  if (Number.isNaN(end.getTime())) return null;
  const now = new Date();
  const months = (end.getFullYear() - now.getFullYear()) * 12 + (end.getMonth() - now.getMonth());
  return Math.max(1, months);
}

function goalExpectedPct(g) {
  if (!g.deadline || !g.createdAt) return null;
  const start = new Date(g.createdAt).getTime();
  const end = new Date(g.deadline).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return null;
  return Math.min(Math.max(((Date.now() - start) / (end - start)) * 100, 0), 100);
}

function goalCard(g, forecast) {
  const pct = Math.min(Number(g.progressPercentage) || 0, 100);
  const expected = goalExpectedPct(g);
  const months = monthsUntil(g.deadline);
  const remaining = Math.max(0, Number(g.targetAmount) - Number(g.savedAmount));
  const required = months ? remaining / months : null;
  const overdue = g.deadline ? new Date(g.deadline).getTime() < Date.now() && !g.completed : false;

  // "Behind" is measured the same way the ruler mark is drawn: against the
  // share of the time between the goal's start and its deadline. Mixing in a
  // forecast here would make the sentence disagree with the picture.
  const behind = !g.completed && expected != null && pct + 5 < expected;

  const daysLeft = g.deadline && !g.completed
    ? Math.ceil((new Date(g.deadline).getTime() - Date.now()) / 86400000)
    : null;
  const deadlineFact = g.deadline
    ? (overdue
      ? `${Math.abs(daysLeft)} day${Math.abs(daysLeft) === 1 ? '' : 's'} past the target date`
      : `${daysLeft} day${daysLeft === 1 ? '' : 's'} to go`)
    : '';

  let reco = '';
  if (!g.completed && required != null && !overdue) {
    reco = `<div class="goal-reco"><span>Save <strong>${formatMoney(required, g.currency)}</strong> a month to reach it by ${esc(formatDate(g.deadline))}.</span></div>`;
  } else if (!g.completed && overdue) {
    reco = `<div class="goal-reco"><span>The target date has passed. ${formatMoney(remaining, g.currency)} still to add.</span></div>`;
  }

  return `
    <article class="goal-card" id="goal-card-${g.id}">
      <div class="goal-card-head">
        <div>
          <div class="goal-title">${esc(g.name)}</div>
          <div class="goal-meta-line">
            <span>${formatMoney(remaining, g.currency)} left</span>
            ${deadlineFact ? `<span>${esc(deadlineFact)}</span>` : ''}
            ${behind && !g.completed ? `<span style="color:var(--warn)">behind the pace you set</span>` : ''}
            ${g.completed ? `<span style="color:var(--pos)">completed</span>` : ''}
          </div>
        </div>
        <div class="goal-card-actions">
          ${!g.completed ? `<button class="btn btn-secondary btn-sm" id="add-money-${g.id}">Add money</button>` : ''}
          <button class="btn btn-ghost btn-sm" id="view-goal-${g.id}">Details</button>
          <span class="overflow-wrap">
            <button id="goal-menu-${g.id}" class="btn btn-ghost btn-icon btn-sm" aria-label="More actions for ${escAttr(g.name)}" aria-haspopup="menu">${icon('more-vertical', 'sm')}</button>
            <div class="overflow-menu" id="goal-actions-${g.id}" role="menu" hidden>
              <button id="del-goal-${g.id}" role="menuitem">Delete goal</button>
            </div>
          </span>
        </div>
      </div>

      ${goalRuler(pct, { expected, label: `${g.name}: ${formatMoney(g.savedAmount, g.currency)} of ${formatMoney(g.targetAmount, g.currency)} saved` })}

      <div class="goal-figures">
        <span><strong>${formatMoney(g.savedAmount, g.currency)}</strong> saved of <strong>${formatMoney(g.targetAmount, g.currency)}</strong></span>
        <span>${pct.toFixed(0)}%</span>
      </div>
      ${reco}
    </article>`;
}

/** The right column: what the open goals add up to and what they need monthly. */
function renderPlan(activeGoals) {
  const side = document.getElementById('goals-side');
  if (!side) return;
  const goals = (activeGoals || []).filter(g => !g.completed);
  if (!goals.length) { side.innerHTML = ''; return; }

  const currency = goals[0].currency || Auth.getDefaultCurrency();
  const target = goals.reduce((sum, g) => sum + (Number(g.targetAmount) || 0), 0);
  const saved = goals.reduce((sum, g) => sum + (Number(g.savedAmount) || 0), 0);
  const remaining = Math.max(0, target - saved);
  const pct = target > 0 ? (saved / target) * 100 : 0;

  let monthly = 0;
  let dated = 0;
  goals.forEach(g => {
    const months = monthsUntil(g.deadline);
    if (months) {
      dated += 1;
      monthly += Math.max(0, Number(g.targetAmount) - Number(g.savedAmount)) / months;
    }
  });

  side.innerHTML = `
    <section class="plan-panel">
      <h3>Savings plan</h3>
      <div class="plan-figure">${formatMoney(remaining, currency)}</div>
      <p class="plan-caption">still to save across ${goals.length} open goal${goals.length === 1 ? '' : 's'}.</p>
      <div class="plan-rows">
        <div class="plan-row"><span>Saved so far</span><span>${formatMoney(saved, currency)} of ${formatMoney(target, currency)}</span></div>
        <div class="plan-row"><span>Overall</span><span>${pct.toFixed(0)}%</span></div>
        ${monthly > 0 ? `<div class="plan-row"><span>Needed each month</span><span>${formatMoney(monthly, currency)}</span></div>` : ''}
      </div>
      <p class="plan-note">${monthly > 0
        ? `The monthly figure adds up each goal's own deadline (${dated} dated goal${dated === 1 ? '' : 's'}).`
        : 'Add a target date to a goal to see what it needs each month.'}</p>
    </section>`;
}

function periodEnd(b) {
  const now = new Date();
  if (b.customEndDate) {
    const d = new Date(b.customEndDate);
    if (!Number.isNaN(d.getTime())) return d;
  }
  if (b.period === 'WEEKLY') {
    const d = new Date(now);
    const day = (d.getDay() + 6) % 7; // Monday = 0
    d.setDate(d.getDate() + (6 - day));
    return d;
  }
  if (b.period === 'CUSTOM') return null;
  return new Date(now.getFullYear(), now.getMonth() + 1, 0);
}

function budgetCard(b) {
  const pct = Math.min(Number(b.utilizationPercentage) || 0, 100);
  const color = budgetStatusColor(b.status);
  const remaining = Number(b.budgetAmount) - Number(b.spentAmount);
  let periodText = titleCase(b.period);
  if (b.period === 'CUSTOM' && b.customStartDate && b.customEndDate) {
    periodText = `${formatDate(b.customStartDate)} – ${formatDate(b.customEndDate)}`;
  }

  const end = periodEnd(b);
  const daysLeft = end ? Math.max(0, Math.ceil((end.getTime() - Date.now()) / 86400000)) : null;

  return `
    <article class="budget-card" id="budget-card-${b.budgetId}">
      <div class="budget-line">
        <div>
          <div class="goal-title">${esc(formatCategory(b.expenseCategory))}</div>
          <div class="budget-period">${esc(periodText)}${daysLeft != null ? ` · ${daysLeft} day${daysLeft === 1 ? '' : 's'} left` : ''}${b.carryForward ? ' · carries over' : ''}</div>
        </div>
        <div style="display:flex;align-items:center;gap:var(--s2)">
          <span class="budget-period" style="color:${b.status === 'EXCEEDED' ? 'var(--neg)' : b.status === 'WARNING' ? 'var(--warn)' : 'var(--text-2)'}">
            ${remaining >= 0 ? `${formatMoney(remaining, b.currency)} left` : `${formatMoney(Math.abs(remaining), b.currency)} over`}
          </span>
          <span class="overflow-wrap">
            <button id="budget-menu-${b.budgetId}" class="btn btn-ghost btn-icon btn-sm" aria-label="More actions for ${esc(formatCategory(b.expenseCategory))}" aria-haspopup="menu">${icon('more-vertical', 'sm')}</button>
            <div class="overflow-menu" id="budget-actions-${b.budgetId}" role="menu" hidden>
              <button id="del-budget-${b.budgetId}" role="menuitem">Delete budget</button>
            </div>
          </span>
        </div>
      </div>
      ${progressBar(pct, color)}
      <div class="budget-figures">
        <span><strong>${formatMoney(b.spentAmount, b.currency)}</strong> spent of <strong>${formatMoney(b.budgetAmount, b.currency)}</strong></span>
        <span>${pct.toFixed(0)}%</span>
      </div>
    </article>`;
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

async function deleteGoal(id, uid, cardElement) {
  if (!(await confirmModal('Delete goal', 'This goal and its contribution history will be removed. This cannot be undone.', 'Delete'))) return;
  if (cardElement) cardElement.style.opacity = '0.4';
  try {
    await api.delete(`/upsert/goals/${id}`, { userId: uid });
    toast('Goal deleted', 'success');
    if (cardElement) cardElement.remove();
    loadGoals(uid);
  } catch (e) {
    if (cardElement) cardElement.style.opacity = '1';
    toast(e.message, 'error');
  }
}

async function deleteBudget(id, uid, cardElement) {
  if (!(await confirmModal('Delete budget', 'This budget will be removed. Transactions are not affected.', 'Delete'))) return;
  if (cardElement) cardElement.style.opacity = '0.4';
  try {
    await api.delete(`/upsert/budgets/${id}`, { userId: uid });
    toast('Budget deleted', 'success');
    if (cardElement) cardElement.remove();
    loadBudgets(uid);
  } catch (e) {
    if (cardElement) cardElement.style.opacity = '1';
    toast(e.message, 'error');
  }
}

/* Delegated so the generated row markup carries no inline handler. */
document.addEventListener('click', (e) => {
  const btn = e.target instanceof Element ? e.target.closest('[data-delete-contribution]') : null;
  if (!btn) return;
  e.preventDefault();
  window.deleteContribution(
    Number(btn.dataset.deleteContribution),
    Number(btn.dataset.goalId),
    btn.dataset.userId,
  );
});

window.deleteContribution = async function (txnId, goalId, uid) {
  if (!(await confirmModal('Delete contribution', 'The goal progress will go down by this amount.', 'Delete'))) return;
  try {
    await api.delete(`/upsert/delete/${txnId}`, { userId: uid });
    toast('Contribution deleted', 'success');
    viewGoalDetails({ id: goalId }, uid, true);
    loadGoals(uid);
  } catch (e) { toast(e.message, 'error'); }
};

function addContribution(goal, uid, onDone) {
  openModal('Add money', `
    <form id="contrib-form">
      <p class="modal-lead">Add to <strong>${esc(goal.name)}</strong>. Saved so far: ${formatMoney(goal.savedAmount, goal.currency)}.</p>
      <div class="form-row">
        <div class="form-group" style="flex:1"><label for="c-amount">Amount</label>
          <input class="form-input" id="c-amount" type="text" inputmode="decimal" required placeholder="0.00">
        </div>
        <div class="form-group" style="flex:1"><label for="c-date">Date</label>
          <input class="form-input" id="c-date" type="date" value="${new Date().toISOString().split('T')[0]}">
        </div>
      </div>
      <div class="form-group"><label for="c-desc">Note (optional)</label>
        <input class="form-input" id="c-desc" placeholder="e.g. November savings">
      </div>
      ${modalActions('Cancel', 'Add money')}
    </form>
  `, {
    onSubmit: async () => {
      const cDate = document.getElementById('c-date').value;
      let timeStr = '00:00:00';
      let isoDate = null;
      if (cDate) {
        const today = new Date();
        const todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
        if (cDate === todayStr) {
          timeStr = String(today.getHours()).padStart(2, '0') + ':' +
                    String(today.getMinutes()).padStart(2, '0') + ':' +
                    String(today.getSeconds()).padStart(2, '0');
        }
        isoDate = new Date(`${cDate}T${timeStr}`).toISOString();
      }
      const payload = {
        amount: parseFloat(document.getElementById('c-amount').value),
        description: document.getElementById('c-desc').value,
        createdAt: isoDate,
        currency: goal.currency,
      };
      await api.patch(`/upsert/goals/${goal.id}/contribute?userId=${uid}`, payload);
      toast('Added to goal', 'success');
      if (onDone) onDone();
      else { viewGoalDetails(goal, uid, true); loadGoals(uid); }
    }
  });
}

async function viewGoalDetails(goalParam, uid, reloadData = false) {
  let goal = goalParam;
  if (reloadData) {
    try {
      const goals = await api.get('/upsert/goals', { userId: uid });
      goal = goals.find(g => g.id === goalParam.id) || goalParam;
    } catch { /* keep the stale goal rather than empty the modal */ }
  }

  openModal(goal.name, `
    <div style="margin-bottom:var(--s4)">
      ${progressBar(Math.min(goal.progressPercentage, 100), goal.completed ? 'var(--pos)' : 'var(--pos)')}
      <div class="goal-figures">
        <span><strong>${formatMoney(goal.savedAmount, goal.currency)}</strong> saved</span>
        <span>Target ${formatMoney(goal.targetAmount, goal.currency)}</span>
      </div>
    </div>

    <div class="section-header" style="margin-bottom:var(--s2)">
      <h3 style="font-size:0.875rem">Contributions</h3>
      ${!goal.completed ? '<button class="btn btn-secondary btn-sm" id="btn-add-contrib">Add money</button>' : ''}
    </div>

    <div id="goal-txns-list" style="max-height:300px;overflow-y:auto"></div>
    <div id="goal-txns-pagination" class="pagination"></div>
  `);

  const txnsContainer = document.getElementById('goal-txns-list');
  let txnsPage = 0;
  let txnsTotal = 1;

  const loadTxns = async () => {
    try {
      const res = await api.get(`/upsert/goals/${goal.id}/transactions`, { userId: uid, page: txnsPage, size: 8 });
      txnsTotal = res.totalPages || 1;
      const txns = res.content || [];
      if (!txns.length) {
        txnsContainer.innerHTML = '<p class="meta" style="padding:16px 0">No contributions yet.</p>';
      } else {
        txnsContainer.innerHTML = txns.map(t => `
          <div class="member-row">
            <div>
              <div style="font-weight:600">${formatMoney(t.amount, t.currency || goal.currency)}</div>
              <div class="meta">${esc(formatDate(t.createdAt))} · ${esc(t.description || 'Contribution')}</div>
            </div>
            <button class="btn btn-ghost btn-icon btn-sm" data-delete-contribution="${t.id}" data-goal-id="${goal.id}" data-user-id="${escAttr(uid)}" aria-label="Delete contribution">${icon('trash', 'sm')}</button>
          </div>`).join('');
      }
      renderTxnsPagination();
    } catch (e) {
      // A 401 means the session ended; the shell is already switching to the
      // login view, so don't misreport it as a data error.
      const authFailure = /session expired/i.test(e?.message || '');
      txnsContainer.innerHTML = authFailure
        ? '<p class="meta" style="padding:10px">Session expired. Please sign in again.</p>'
        : '<p style="color:var(--neg);padding:10px">Could not load contributions.</p>';
    }
  };

  const renderTxnsPagination = () => {
    const el = document.getElementById('goal-txns-pagination');
    if (!el || txnsTotal <= 1) { if (el) el.innerHTML = ''; return; }
    let html = `<button class="btn-icon" id="gp-prev" ${txnsPage === 0 ? 'disabled' : ''} aria-label="Previous page">${icon('chevron-left', 'sm')}</button>`;
    let startPage = Math.max(0, txnsPage - 2);
    let endPage = Math.min(txnsTotal - 1, startPage + 4);
    if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
    for (let i = startPage; i <= endPage; i++) {
      html += `<button class="${i === txnsPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`;
    }
    html += `<button class="btn-icon" id="gp-next" ${txnsPage === txnsTotal - 1 ? 'disabled' : ''} aria-label="Next page">${icon('chevron-right', 'sm')}</button>`;
    el.innerHTML = html;
    if (txnsPage > 0) document.getElementById('gp-prev').onclick = () => { txnsPage--; loadTxns(); };
    if (txnsPage < txnsTotal - 1) document.getElementById('gp-next').onclick = () => { txnsPage++; loadTxns(); };
    el.querySelectorAll('button[data-p]').forEach(b => { b.onclick = () => { txnsPage = +b.dataset.p; loadTxns(); }; });
  };

  loadTxns();

  document.getElementById('btn-add-contrib')?.addEventListener('click', () => {
    document.querySelector('.modal-overlay')?.remove();
    addContribution(goal, uid, () => { loadGoals(uid); });
  });
}

/** Average monthly spend per category over the last 3 months, for suggestions. */
async function loadCategoryAverages(uid) {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth() - 2, 1);
  const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  try {
    const data = await api.get('/analytics/category-pie-chart', {
      userId: uid,
      transactionFilter: 'EXPENSE',
      timelineType: 'MONTHLY',
      tzOffsetMinutes: -new Date().getTimezoneOffset(),
      startDate: `${iso(start)}T00:00:00`,
      endDate: `${iso(new Date(now.getFullYear(), now.getMonth() + 1, 0))}T23:59:59.999`,
    });
    const map = {};
    (data?.labels || []).forEach((label, i) => {
      map[label] = (Number(data.datasets?.[0]?.data?.[i]) || 0) / 3;
    });
    return map;
  } catch { return {}; }
}

function bindEvents(uid) {
  const addGoalBtn = document.getElementById('add-goal-btn');
  if (addGoalBtn) {
    addGoalBtn.onclick = () => {
      openModal('New savings goal', `
        <form id="goal-form">
          <div class="form-group"><label for="g-name">Goal name</label><input class="form-input" id="g-name" required placeholder="e.g. Emergency fund"></div>
          <div class="form-row">
            <div class="form-group"><label for="g-target">Target amount</label><input class="form-input" id="g-target" type="text" inputmode="decimal" required></div>
            <div class="form-group"><label for="g-currency">Currency</label><select class="form-select" id="g-currency">${currencyOptionHtml(Auth.getDefaultCurrency())}</select></div>
          </div>
          <div class="form-row">
            <div class="form-group"><label for="g-priority">Priority</label>
              <select class="form-select" id="g-priority">
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High</option>
                <option value="MEDIUM" selected>Medium</option>
                <option value="LOW">Low</option>
              </select>
            </div>
            <div class="form-group"><label for="g-deadline">Target date</label><input class="form-input" id="g-deadline" type="date"></div>
          </div>
          <div class="form-group"><label for="g-desc">Note (optional)</label><input class="form-input" id="g-desc" placeholder="Why this goal matters"></div>
          ${modalActions('Cancel', 'Create goal')}
        </form>`, {
        onSubmit: async () => {
          await api.post('/upsert/goals', {
            userId: uid,
            name: document.getElementById('g-name').value,
            targetAmount: parseFloat(document.getElementById('g-target').value),
            currency: document.getElementById('g-currency').value.toUpperCase(),
            description: document.getElementById('g-desc').value,
            deadline: document.getElementById('g-deadline').value || null,
            priority: document.getElementById('g-priority').value,
          });
          toast('Goal created', 'success');
          loadGoals(uid);
        }
      });
    };
  }

  const addBudgetBtn = document.getElementById('add-budget-btn');
  if (addBudgetBtn) {
    addBudgetBtn.onclick = async () => {
      const averages = await loadCategoryAverages(uid);
      openModal('New category budget', `
        <form id="budget-form">
          <div class="form-group">
            <label for="b-cat">Category</label>
            <select class="form-select" id="b-cat">${categoryOptions(EXPENSE_CATS)}</select>
          </div>
          <div class="form-row">
            <div class="form-group"><label for="b-amount">Budget amount</label><input class="form-input" id="b-amount" type="text" inputmode="decimal" required></div>
            <div class="form-group"><label for="b-period">Period</label>
              <select class="form-select" id="b-period">
                <option value="MONTHLY">Monthly</option>
                <option value="WEEKLY">Weekly</option>
                <option value="CUSTOM">Custom range</option>
              </select>
            </div>
          </div>
          <p class="form-help" id="b-suggestion"></p>

          <div class="form-row" id="b-custom-dates" style="display:none">
            <div class="form-group"><label for="b-start">Start date</label><input class="form-input" id="b-start" type="date"></div>
            <div class="form-group"><label for="b-end">End date</label><input class="form-input" id="b-end" type="date"></div>
          </div>

          <div class="form-row">
            <div class="form-group"><label for="b-currency">Currency</label><select class="form-select" id="b-currency">${currencyOptionHtml(Auth.getDefaultCurrency())}</select></div>
            <div class="form-group form-check" style="margin-top:26px">
              <input type="checkbox" id="b-carry">
              <label for="b-carry">Carry forward the remainder</label>
            </div>
          </div>
          ${modalActions('Cancel', 'Create budget')}
        </form>`, {
        onSubmit: async () => {
          const payload = {
            userId: uid,
            expenseCategory: document.getElementById('b-cat').value,
            budgetAmount: parseFloat(document.getElementById('b-amount').value),
            period: document.getElementById('b-period').value,
            currency: document.getElementById('b-currency').value.toUpperCase(),
            carryForward: document.getElementById('b-carry').checked,
          };
          if (payload.period === 'CUSTOM') {
            payload.customStartDate = document.getElementById('b-start').value || null;
            payload.customEndDate = document.getElementById('b-end').value || null;
          }
          await api.post('/upsert/budgets', payload);
          toast('Budget created', 'success');
          loadBudgets(uid);
        }
      });

      const catSel = document.getElementById('b-cat');
      const amountInput = document.getElementById('b-amount');
      const suggestion = document.getElementById('b-suggestion');
      const syncSuggestion = () => {
        const avg = averages[catSel.value] || 0;
        if (avg > 0) {
          suggestion.textContent = `You spent about ${formatMoney(avg, Auth.getDefaultCurrency())} a month here over the last 3 months.`;
          if (!amountInput.value) amountInput.value = avg.toFixed(2);
        } else {
          suggestion.textContent = 'No recent spending in this category.';
        }
      };
      catSel.addEventListener('change', syncSuggestion);
      syncSuggestion();

      const periodSel = document.getElementById('b-period');
      const customDiv = document.getElementById('b-custom-dates');
      periodSel.addEventListener('change', () => {
        customDiv.style.display = periodSel.value === 'CUSTOM' ? 'flex' : 'none';
      });
    };
  }
}
