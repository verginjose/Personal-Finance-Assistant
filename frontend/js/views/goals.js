import { api, Auth, toast } from '../utils/api.js?v=2026110417';
import { icon } from '../utils/icons.js?v=2026110417';
import {
  esc, pageHeader, emptyState, formatMoney, formatCategory, formatDate,
  progressBar, budgetStatusColor, badge, openModal, confirmModal, modalActions,
  EXPENSE_CATS, categoryOptions, setupCategorySearch
} from '../utils/ui.js?v=2026110417';

export async function renderGoals(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Goals & Budgets', '')}
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

  bindEvents(userId);
  await Promise.all([loadGoals(userId), loadBudgets(userId)]);
}

async function loadGoals(uid) {
  const el = document.getElementById('goals-list');
  try {
    const goals = await api.get('/upsert/goals', { userId: uid });
    const forecasts = await Promise.allSettled(
      goals.filter(g => !g.completed).map(g => api.get(`/analytics/goals/${g.id}/forecast`, { userId: uid }))
    );
    const forecastById = new Map();
    goals.filter(g => !g.completed).forEach((g, i) => {
      if (forecasts[i]?.status === 'fulfilled') forecastById.set(g.id, forecasts[i].value);
    });

    el.innerHTML = goals.length ? goals.map(g => goalCard(g, forecastById.get(g.id))).join('') :
      emptyState('target', 'No savings goals', 'Create a goal to start tracking progress.');
    goals.forEach(g => {
      bindOverflow(`goal-menu-${g.id}`, `goal-actions-${g.id}`);
      document.getElementById(`del-goal-${g.id}`)?.addEventListener('click', (e) => deleteGoal(g.id, uid, e.target.closest('.card')));
      document.getElementById(`view-goal-${g.id}`)?.addEventListener('click', () => viewGoalDetails(g, uid));
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
    el.innerHTML = budgets.length ? budgets.map(budgetCard).join('') :
      emptyState('bar-chart', 'No budgets set', 'Set category limits to track spending.');
    budgets.forEach(b => {
      bindOverflow(`budget-menu-${b.budgetId}`, `budget-actions-${b.budgetId}`);
      document.getElementById(`del-budget-${b.budgetId}`)?.addEventListener('click', (e) => deleteBudget(b.budgetId, uid, e.target.closest('.card')));
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

function goalCard(g, forecast) {
  const pct = Math.min(g.progressPercentage, 100);
  const color = g.completed ? 'var(--accent-g)' : pct >= 80 ? 'var(--accent-y)' : 'var(--primary)';

  let statusHtml = '';
  let behind = false;
  if (!g.completed && g.deadline) {
    const months = monthsUntil(g.deadline);
    const required = months ? Math.max(0, (Number(g.targetAmount) - Number(g.savedAmount)) / months) : null;
    if (required != null && forecast) {
      const actual = Number(forecast.monthlyVelocity) || 0;
      behind = actual < required;
      statusHtml = `
        <div class="goal-status ${behind ? 'is-behind' : ''}">
          <strong>${formatMoney(required, g.currency)}</strong>/mo needed · saving <strong>${formatMoney(actual, g.currency)}</strong>/mo
        </div>`;
    } else if (required != null) {
      // The forecast request failed: state the requirement, not a made-up 0.
      statusHtml = `
        <div class="goal-status">
          <strong>${formatMoney(required, g.currency)}</strong>/mo needed to hit the deadline.
        </div>`;
    }
  } else if (!g.completed && forecast?.message) {
    statusHtml = `<div class="goal-status">${esc(forecast.message)}</div>`;
  }

  return `
    <div class="card goal-card">
      <div class="section-header" style="margin-bottom:10px">
        <div>
          <h4 style="font-weight:600;margin-bottom:4px">${g.completed ? icon('check-circle','sm') + ' ' : ''}${esc(g.name)}</h4>
          <div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">
            ${behind ? '<span class="badge badge-warning">Behind</span>' : ''}
            ${g.completed ? '<span class="badge badge-income">Complete</span>' : ''}
            ${g.deadline ? `<span style="font-size:0.75rem;color:var(--text-muted)">${formatDate(g.deadline)}</span>` : ''}
          </div>
        </div>
        <div style="display:flex;gap:6px;align-items:center">
          <button id="view-goal-${g.id}" class="btn btn-secondary btn-sm">Details</button>
          <span class="overflow-wrap">
            <button id="goal-menu-${g.id}" class="btn btn-ghost btn-icon btn-sm" aria-label="Goal actions">${icon('more-vertical', 'sm')}</button>
            <div class="overflow-menu" id="goal-actions-${g.id}" hidden>
              <button id="del-goal-${g.id}">Delete goal</button>
            </div>
          </span>
        </div>
      </div>
      ${progressBar(pct, color, true)}
      <div style="display:flex;justify-content:space-between;font-size:.82rem;color:var(--text-dim);margin-top:8px">
        <span>${formatMoney(g.savedAmount, g.currency)} saved</span>
        <span>${formatMoney(g.targetAmount - g.savedAmount, g.currency)} remaining</span>
      </div>
      ${statusHtml}
    </div>`;
}


function budgetCard(b) {
  const pct = Math.min(b.utilizationPercentage, 100);
  const color = budgetStatusColor(b.status);
  const remaining = b.budgetAmount - b.spentAmount;
  let periodText = titleCase(b.period);
  if (b.period === 'CUSTOM' && b.customStartDate && b.customEndDate) {
    periodText = `${formatDate(b.customStartDate)} – ${formatDate(b.customEndDate)}`;
  }

  // A status is only worth showing when it needs action.
  let statusText = '';
  if (b.status === 'EXCEEDED') {
    statusText = `Over by ${formatMoney(Math.abs(remaining), b.currency)}`;
  } else if (b.status === 'WARNING') {
    statusText = `${b.utilizationPercentage.toFixed(0)}% used · ${formatMoney(remaining, b.currency)} left`;
  }

  return `
    <div class="card budget-card" style="--budget-color:${color}">
      <div class="section-header" style="margin-bottom:8px">
        <div>
          <h4 style="font-weight:600">${esc(formatCategory(b.expenseCategory))}</h4>
          <div style="display:flex;gap:8px;align-items:center;margin-top:4px">
            <span style="font-size:.75rem;color:var(--text-muted)">${periodText}</span>
            ${b.carryForward ? `<span class="txn-tag">${icon('repeat', 'xs')} Rollover</span>` : ''}
          </div>
        </div>
        <span class="overflow-wrap">
          <button id="budget-menu-${b.budgetId}" class="btn btn-ghost btn-icon btn-sm" aria-label="Budget actions">${icon('more-vertical', 'sm')}</button>
          <div class="overflow-menu" id="budget-actions-${b.budgetId}" hidden>
            <button id="del-budget-${b.budgetId}">Delete budget</button>
          </div>
        </span>
      </div>
      ${progressBar(pct, color, true)}
      <div style="display:flex;justify-content:space-between;font-size:.82rem;color:var(--text-dim);margin-top:8px">
        <span>${formatMoney(b.spentAmount, b.currency)} of ${formatMoney(b.budgetAmount, b.currency)}</span>
        ${statusText ? `<span style="color:${color};font-weight:600">${statusText}</span>` : ''}
      </div>
    </div>`;
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}


async function deleteGoal(id, uid, cardElement) {
  if (!(await confirmModal('Delete Goal', 'Are you sure you want to delete this goal? This action cannot be undone.', 'Delete'))) return;
  if (cardElement) {
    cardElement.style.transition = 'all 0.3s';
    cardElement.style.opacity = '0.3';
    cardElement.style.transform = 'scale(0.98)';
  }
  try { 
    await api.delete(`/upsert/goals/${id}`, { userId: uid }); 
    toast('Goal deleted', 'success'); 
    if (cardElement) cardElement.remove();
    loadGoals(uid); 
  }
  catch (e) { 
    if (cardElement) {
      cardElement.style.opacity = '1';
      cardElement.style.transform = 'none';
    }
    toast(e.message, 'error'); 
  }
}

async function deleteBudget(id, uid, cardElement) {
  if (!(await confirmModal('Delete Budget', 'Are you sure you want to delete this budget? This action cannot be undone.', 'Delete'))) return;
  if (cardElement) {
    cardElement.style.transition = 'all 0.3s';
    cardElement.style.opacity = '0.3';
    cardElement.style.transform = 'scale(0.98)';
  }
  try { 
    await api.delete(`/upsert/budgets/${id}`, { userId: uid }); 
    toast('Budget deleted', 'success'); 
    if (cardElement) cardElement.remove();
    loadBudgets(uid); 
  }
  catch (e) { 
    if (cardElement) {
      cardElement.style.opacity = '1';
      cardElement.style.transform = 'none';
    }
    toast(e.message, 'error'); 
  }
}

window.deleteContribution = async function(txnId, goalId, uid) {
  if (!(await confirmModal('Delete Contribution', 'Are you sure you want to delete this contribution? The goal progress will decrease.', 'Delete'))) return;
  try {
    await api.delete(`/upsert/delete/${txnId}`, { userId: uid });
    toast('Contribution deleted', 'success');
    viewGoalDetails({id: goalId}, uid, true); // reload
    loadGoals(uid);
  } catch(e) { toast(e.message, 'error'); }
};

async function viewGoalDetails(goalParam, uid, reloadData = false) {
  let goal = goalParam;
  if (reloadData) {
      const goals = await api.get('/upsert/goals', { userId: uid });
      goal = goals.find(g => g.id === goalParam.id);
  }
  
  let txnsHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
  
  openModal(`Goal: ${esc(goal.name)}`, `
    <div style="margin-bottom:16px;">
      ${progressBar(Math.min(goal.progressPercentage, 100), goal.completed ? 'var(--accent-g)' : 'var(--primary)')}
      <div style="display:flex;justify-content:space-between;font-size:.85rem;color:var(--text-dim);margin-top:8px">
        <span>${formatMoney(goal.savedAmount, goal.currency)} saved</span>
        <span>Target: ${formatMoney(goal.targetAmount, goal.currency)}</span>
      </div>
    </div>
    
    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
      <h4 style="margin:0; font-weight:600;">Contribution History</h4>
      ${!goal.completed ? `<button class="btn btn-secondary btn-sm" id="btn-add-contrib">+ Add Contribution</button>` : ''}
    </div>
    
    <div id="goal-txns-list" style="max-height:300px; overflow-y:auto;">
      ${txnsHTML}
    </div>
    <div id="goal-txns-pagination" class="pagination" style="margin-top: 12px; justify-content: center;"></div>
  `);

  const txnsContainer = document.getElementById('goal-txns-list');
  let txnsPage = 0;
  let txnsTotal = 1;

  const loadTxns = async () => {
    try {
      const res = await api.get(`/upsert/goals/${goal.id}/transactions`, { userId: uid, page: txnsPage, size: 8 });
      txnsTotal = res.totalPages || 1;
      const txns = res.content || [];
      if (!txns || txns.length === 0) {
        txnsContainer.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted);font-size:0.9rem">No contributions yet.</div>';
      } else {
        txnsContainer.innerHTML = txns.map(t => `
          <div class="card" style="padding:10px; margin-bottom:8px; display:flex; justify-content:space-between; align-items:center;">
             <div>
               <div style="font-weight:600; font-size:0.95rem;">${formatMoney(t.amount, t.currency)}</div>
               <div style="font-size:0.8rem; color:var(--text-dim);">${formatDate(t.createdAt)} • ${esc(t.description || 'Contribution')}</div>
             </div>
             <button class="btn btn-danger btn-icon btn-sm" onclick="window.deleteContribution(${t.id}, ${goal.id}, '${uid}')" title="Delete Contribution">
                ${icon('trash', 'xs')}
             </button>
          </div>
        `).join('');
      }
      renderTxnsPagination();
    } catch(e) {
      txnsContainer.innerHTML = `<div style="color:var(--accent-r);padding:10px">Failed to load history</div>`;
    }
  };

  const renderTxnsPagination = () => {
    const el = document.getElementById('goal-txns-pagination');
    if (!el || txnsTotal <= 1) { if (el) el.innerHTML = ''; return; }
    
    let html = `<button class="btn btn-secondary btn-icon" id="gp-prev" ${txnsPage === 0 ? 'disabled' : ''}>${icon('chevron-left', 'sm')}</button>`;
    
    let startPage = Math.max(0, txnsPage - 2);
    let endPage = Math.min(txnsTotal - 1, startPage + 4);
    if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
    
    for (let i = startPage; i <= endPage; i++) {
      html += `<button class="${i === txnsPage ? 'active' : ''}" data-p="${i}">${i + 1}</button>`;
    }
    
    html += `<button class="btn btn-secondary btn-icon" id="gp-next" ${txnsPage === txnsTotal - 1 ? 'disabled' : ''}>${icon('chevron-right', 'sm')}</button>`;
    
    el.innerHTML = html;
    
    if (txnsPage > 0) {
      document.getElementById('gp-prev').onclick = () => { txnsPage--; loadTxns(); };
    }
    if (txnsPage < txnsTotal - 1) {
      document.getElementById('gp-next').onclick = () => { txnsPage++; loadTxns(); };
    }
    el.querySelectorAll('button[data-p]').forEach(b => {
      b.onclick = () => { txnsPage = +b.dataset.p; loadTxns(); };
    });
  };

  loadTxns();

  document.getElementById('btn-add-contrib')?.addEventListener('click', () => {
    openModal('Add Contribution', `
      <form id="contrib-form">
        <div class="form-row">
          <div class="form-group" style="flex:1"><label for="c-amount">Amount</label>
            <input class="form-input" id="c-amount" type="text" inputmode="decimal" required placeholder="Amount to add">
          </div>
          <div class="form-group" style="flex:1"><label for="c-date">Date</label>
            <input class="form-input" id="c-date" type="date" value="${new Date().toISOString().split('T')[0]}">
          </div>
        </div>
        <div class="form-group"><label for="c-desc">Description (Optional)</label>
          <input class="form-input" id="c-desc" placeholder="e.g. June savings">
        </div>
        ${modalActions('Cancel', 'Save Contribution')}
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
          currency: goal.currency
        };
        await api.patch(`/upsert/goals/${goal.id}/contribute?userId=${uid}`, payload);
        toast('Contribution added successfully', 'success');
        viewGoalDetails(goal, uid, true);
        loadGoals(uid);
      }
    });
  });
}

function bindEvents(uid) {
  const addGoalBtn = document.getElementById('add-goal-btn');
  const addBudgetBtn = document.getElementById('add-budget-btn');
  
  if (addGoalBtn) {
    addGoalBtn.onclick = () => {
      openModal('New Savings Goal', `
      <form id="goal-form">
        <div class="form-group"><label for="g-name">Goal Name</label><input class="form-input" id="g-name" required placeholder="MacBook Pro"></div>
        <div class="form-row">
          <div class="form-group"><label for="g-target">Target Amount</label><input class="form-input" id="g-target" type="text" inputmode="decimal" required></div>
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
            userId:  uid,
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
  }

  if (addBudgetBtn) {
    addBudgetBtn.onclick = () => {
      openModal('New Category Budget', `
        <form id="budget-form">
        <div class="form-group" id="b-cat-group">
          <label for="b-cat">Category</label>
          <input type="text" id="b-cat-search" class="form-input category-search" placeholder="Search category..." style="margin-bottom: 8px;">
          <select class="form-select category-select" id="b-cat">${categoryOptions(EXPENSE_CATS)}</select>
        </div>
        <div class="form-row">
          <div class="form-group"><label for="b-amount">Budget Amount</label><input class="form-input" id="b-amount" type="text" inputmode="decimal" required></div>
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

    setupCategorySearch('b-cat-search', 'b-cat');

    const periodSel = document.getElementById('b-period');
    const customDiv = document.getElementById('b-custom-dates');
    periodSel.addEventListener('change', () => {
        customDiv.style.display = periodSel.value === 'CUSTOM' ? 'flex' : 'none';
    });
  };
  }
}
