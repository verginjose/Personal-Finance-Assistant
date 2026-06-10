import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, pageHeader, emptyState, formatCurrency, formatCategory, formatDate,
  progressBar, budgetStatusColor, budgetStatusBadge, badge, openModal, modalActions,
  EXPENSE_CATS, categoryOptions
} from '../utils/ui.js';

export async function renderGoals(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Goals & Budgets', 'Set savings targets and category spending limits')}
    <div class="goals-layout fade-up">
      <div>
        <div class="section-header">
          <h2>Savings Goals</h2>
          <button class="btn btn-primary btn-sm" id="add-goal-btn">+ New Goal</button>
        </div>
        <div id="goals-list"><div class="spinner-center"><span class="spinner"></span></div></div>
      </div>
      <div>
        <div class="section-header">
          <h2>Category Budgets</h2>
          <button class="btn btn-primary btn-sm" id="add-budget-btn">+ New Budget</button>
        </div>
        <div id="budgets-list"><div class="spinner-center"><span class="spinner"></span></div></div>
      </div>
    </div>`;

  await Promise.all([loadGoals(userId), loadBudgets(userId)]);
  bindEvents(userId);
}

async function loadGoals(uid) {
  const el = document.getElementById('goals-list');
  try {
    const goals = await api.get('/upsert/goals', { userId: uid });
    el.innerHTML = goals.length ? goals.map(goalCard).join('') :
      emptyState('target', 'No savings goals', 'Create a goal to start tracking progress.');
    goals.forEach(g => {
      document.getElementById(`del-goal-${g.id}`)?.addEventListener('click', () => deleteGoal(g.id, uid));
      document.getElementById(`contrib-goal-${g.id}`)?.addEventListener('click', () => contributeToGoal(g.id, uid));
    });
  } catch (e) { toast(e.message, 'error'); }
}

async function loadBudgets(uid) {
  const el = document.getElementById('budgets-list');
  try {
    const budgets = await api.get('/upsert/budgets', { userId: uid });
    el.innerHTML = budgets.length ? budgets.map(budgetCard).join('') :
      emptyState('bar-chart', 'No budgets set', 'Set category limits to track spending.');
    budgets.forEach(b => {
      document.getElementById(`del-budget-${b.budgetId}`)?.addEventListener('click', () => deleteBudget(b.budgetId, uid));
    });
  } catch (e) { toast(e.message, 'error'); }
}

function goalCard(g) {
  const pct = Math.min(g.progressPercentage, 100);
  const color = g.completed ? 'var(--accent-g)' : pct >= 80 ? 'var(--accent-y)' : 'var(--primary)';
  const priorityBadge = g.priority ? `<span class="badge" style="background:var(--bg-lighter);margin-left:8px;font-size:0.7rem;vertical-align:middle;">${esc(g.priority)} PRIORITY</span>` : '';
  return `
    <div class="card goal-card">
      <div class="section-header" style="margin-bottom:8px">
        <h4 style="font-weight:700">${esc(g.name)} ${g.completed ? badge('Complete', 'income') : ''}${priorityBadge}</h4>
        <div style="display:flex;gap:6px">
          ${!g.completed ? `<button id="contrib-goal-${g.id}" class="btn btn-success btn-sm">Contribute</button>` : ''}
          <button id="del-goal-${g.id}" class="btn btn-danger btn-icon btn-sm" aria-label="Delete goal">${icon('trash', 'sm')}</button>
        </div>
      </div>
      ${progressBar(pct, color)}
      <div style="display:flex;justify-content:space-between;font-size:.82rem;color:var(--text-dim);margin-top:10px">
        <span>${formatCurrency(g.savedAmount, g.currency)} saved</span>
        <span>${pct.toFixed(1)}% of ${formatCurrency(g.targetAmount, g.currency)}</span>
      </div>
      ${g.deadline ? `<div style="font-size:.78rem;color:var(--text-muted);margin-top:6px">🗓 Deadline: ${formatDate(g.deadline)}</div>` : ''}
    </div>`;
}

function budgetCard(b) {
  const pct = Math.min(b.utilizationPercentage, 100);
  const color = budgetStatusColor(b.status);
  let periodText = esc(b.period);
  if (b.period === 'CUSTOM' && b.customStartDate && b.customEndDate) {
      periodText = `${formatDate(b.customStartDate)} to ${formatDate(b.customEndDate)}`;
  }
  return `
    <div class="card budget-card" style="--budget-color:${color}">
      <div class="section-header" style="margin-bottom:8px">
        <div>
          <h4 style="font-weight:700">${esc(formatCategory(b.expenseCategory))}</h4>
          <div style="display:flex;gap:8px;align-items:center;margin-top:4px">
            <span style="font-size:.78rem;color:var(--text-muted)">${periodText}</span>
            ${budgetStatusBadge(b.status)}
            ${b.carryForward ? `<span class="badge badge-recurring">${icon('repeat', 'xs')} Rollover</span>` : ''}
          </div>
        </div>
        <button id="del-budget-${b.budgetId}" class="btn btn-danger btn-icon btn-sm" aria-label="Delete budget">${icon('trash', 'sm')}</button>
      </div>
      ${progressBar(pct, color)}
      <div style="display:flex;justify-content:space-between;font-size:.82rem;color:var(--text-dim);margin-top:10px">
        <span>${formatCurrency(b.spentAmount, b.currency)} spent</span>
        <span>Budget: ${formatCurrency(b.budgetAmount, b.currency)}</span>
      </div>
    </div>`;
}

async function deleteGoal(id, uid) {
  if (!confirm('Delete this goal?')) return;
  try { await api.delete(`/upsert/goals/${id}`, { userId: uid }); toast('Goal deleted', 'success'); loadGoals(uid); }
  catch (e) { toast(e.message, 'error'); }
}

async function deleteBudget(id, uid) {
  if (!confirm('Delete this budget?')) return;
  try { await api.delete(`/upsert/budgets/${id}`, { userId: uid }); toast('Budget deleted', 'success'); loadBudgets(uid); }
  catch (e) { toast(e.message, 'error'); }
}

function contributeToGoal(id, uid) {
  openModal('Contribute to Goal', `
    <form id="contrib-form">
      <div class="form-group"><label for="contrib-amt">Amount</label>
        <input class="form-input" id="contrib-amt" type="number" step="0.01" min="0.01" required placeholder="1000"></div>
      ${modalActions('Cancel', 'Contribute')}
    </form>`, {
    onSubmit: async () => {
      const amt = document.getElementById('contrib-amt').value;
      await api.patch(`/upsert/goals/${id}/contribute`, null, { userId: uid, amount: amt });
      toast(`₹${amt} contributed!`, 'success');
      loadGoals(uid);
    }
  });
}

function bindEvents(uid) {
  document.getElementById('add-goal-btn').onclick = () => {
    openModal('New Savings Goal', `
      <form id="goal-form">
        <div class="form-group"><label for="g-name">Goal Name</label><input class="form-input" id="g-name" required placeholder="MacBook Pro"></div>
        <div class="form-row">
          <div class="form-group"><label for="g-target">Target Amount</label><input class="form-input" id="g-target" type="number" min="1" required></div>
          <div class="form-group"><label for="g-currency">Currency</label><input class="form-input" id="g-currency" value="INR" maxlength="3"></div>
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
            <div class="form-group"><label for="g-deadline">Deadline</label><input class="form-input" id="g-deadline" type="date"></div>
        </div>
        <div class="form-group"><label for="g-desc">Description</label><input class="form-input" id="g-desc" placeholder="Optional"></div>
        ${modalActions('Cancel', 'Create Goal')}
      </form>`, {
      onSubmit: async () => {
        await api.post('/upsert/goals', {
          userId: uid,
          name: document.getElementById('g-name').value,
          targetAmount: parseFloat(document.getElementById('g-target').value),
          currency: document.getElementById('g-currency').value.toUpperCase(),
          description: document.getElementById('g-desc').value,
          deadline: document.getElementById('g-deadline').value || null,
          priority: document.getElementById('g-priority').value
        });
        toast('Goal created!', 'success');
        loadGoals(uid);
      }
    });
  };

  document.getElementById('add-budget-btn').onclick = () => {
    openModal('New Category Budget', `
      <form id="budget-form">
        <div class="form-group"><label for="b-cat">Category</label>
          <select class="form-select" id="b-cat">${categoryOptions(EXPENSE_CATS)}</select></div>
        <div class="form-row">
          <div class="form-group"><label for="b-amount">Budget Amount</label><input class="form-input" id="b-amount" type="number" min="1" required></div>
          <div class="form-group"><label for="b-period">Period</label>
            <select class="form-select" id="b-period">
                <option value="MONTHLY">Monthly</option>
                <option value="WEEKLY">Weekly</option>
                <option value="CUSTOM">Custom Range</option>
            </select>
          </div>
        </div>
        
        <div class="form-row" id="b-custom-dates" style="display:none">
          <div class="form-group"><label for="b-start">Start Date</label><input class="form-input" id="b-start" type="date"></div>
          <div class="form-group"><label for="b-end">End Date</label><input class="form-input" id="b-end" type="date"></div>
        </div>

        <div class="form-row">
            <div class="form-group"><label for="b-currency">Currency</label><input class="form-input" id="b-currency" value="INR" maxlength="3"></div>
            <div class="form-group form-check" style="margin-top:auto;margin-bottom:12px;">
                <input type="checkbox" id="b-carry">
                <label for="b-carry">Carry forward remaining?</label>
            </div>
        </div>
        ${modalActions('Cancel', 'Create Budget')}
      </form>`, {
      onSubmit: async () => {
        const payload = {
          userId: uid,
          expenseCategory: document.getElementById('b-cat').value,
          budgetAmount: parseFloat(document.getElementById('b-amount').value),
          period: document.getElementById('b-period').value,
          currency: document.getElementById('b-currency').value.toUpperCase(),
          carryForward: document.getElementById('b-carry').checked
        };
        if (payload.period === 'CUSTOM') {
            payload.customStartDate = document.getElementById('b-start').value || null;
            payload.customEndDate = document.getElementById('b-end').value || null;
        }
        await api.post('/upsert/budgets', payload);
        toast('Budget created!', 'success');
        loadBudgets(uid);
      }
    });

    const periodSel = document.getElementById('b-period');
    const customDiv = document.getElementById('b-custom-dates');
    periodSel.addEventListener('change', () => {
        customDiv.style.display = periodSel.value === 'CUSTOM' ? 'flex' : 'none';
    });
  };
}
