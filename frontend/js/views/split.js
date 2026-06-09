import { api, Auth, toast } from '../utils/api.js';
import { esc, pageHeader, emptyState, formatCurrency, formatDate, openModal, modalActions } from '../utils/ui.js';

let currentGroupId = null;

export async function renderSplit(container) {
  currentGroupId = null;
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Split Expenses', 'Manage shared expenses with groups', '<button class="btn btn-primary btn-sm" id="sp-create">+ New Group</button>')}
    <div style="margin-bottom:16px">
      <button class="btn btn-secondary btn-sm" id="sp-back" style="display:none">Back to Groups</button>
    </div>
    <div id="sp-content" class="fade-up"></div>`;

  document.getElementById('sp-create').onclick = () => createGroupModal(userId);
  document.getElementById('sp-back').onclick = () => { currentGroupId = null; loadGroups(userId); };
  loadGroups(userId);
}

async function loadGroups(userId) {
  const el = document.getElementById('sp-content');
  document.getElementById('sp-back').style.display = 'none';
  document.getElementById('sp-create').style.display = '';
  el.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
  try {
    const groups = await api.get('/upsert/groups', { userId });
    if (!groups.length) {
      el.innerHTML = emptyState('users', 'No groups yet', 'Create a group to start splitting expenses.');
      return;
    }
    el.innerHTML = `<div class="card-grid card-grid-3">${groups.map(g => `
      <div class="card group-card" data-id="${g.id}" role="button" tabindex="0">
        <div class="group-name">${esc(g.name)}</div>
        <div class="group-meta">${esc(g.description || 'No description')}</div>
      </div>`).join('')}</div>`;
    el.querySelectorAll('.group-card').forEach(c => {
      const open = () => loadGroupDetail(+c.dataset.id, userId);
      c.onclick = open;
      c.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } };
    });
  } catch (err) {
    el.innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`;
  }
}

async function loadGroupDetail(groupId, userId) {
  currentGroupId = groupId;
  const el = document.getElementById('sp-content');
  document.getElementById('sp-back').style.display = '';
  document.getElementById('sp-create').style.display = 'none';
  el.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
  try {
    const [group, members, expenses, balances, activity] = await Promise.all([
      api.get(`/upsert/groups/${groupId}`),
      api.get(`/upsert/groups/${groupId}/members`),
      api.get(`/upsert/groups/${groupId}/expenses`),
      api.get(`/upsert/groups/${groupId}/balances`),
      api.get(`/upsert/groups/${groupId}/activity`)
    ]);
    el.innerHTML = `
      <div class="card" style="margin-bottom:20px">
        <h3>${esc(group.name)}</h3>
        <p style="color:var(--text-dim);margin-top:4px">${esc(group.description || '')}</p>
      </div>
      <div class="card-grid card-grid-3">
        <div class="card">
          <div class="card-header"><h3>Members (${members.length})</h3></div>
          ${members.map(m => `<div class="balance-item"><span>@${esc(m.name)}</span></div>`).join('')}
          <button class="btn btn-secondary btn-sm" style="margin-top:12px;width:100%" id="sp-add-member">+ Add Member</button>
        </div>
        <div class="card">
          <div class="card-header"><h3>Expenses (${expenses.length})</h3></div>
          ${expenses.length ? expenses.slice(0, 8).map(e => `
            <div class="balance-item expense-row">
              <span>${esc(e.description || 'Expense')} <span class="badge badge-info" style="font-size:.68rem;margin-left:4px">${esc(splitTypeLabel(e.splitType))}</span></span>
              <div class="expense-row-actions">
                <span style="font-weight:600;color:var(--accent)">${formatCurrency(e.amount)}</span>
                <button type="button" class="btn btn-danger btn-sm delete-expense-btn" data-id="${e.id}" title="Delete expense">×</button>
              </div>
            </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem">No expenses yet</p>'}
          <button class="btn btn-primary btn-sm" style="margin-top:12px;width:100%" id="sp-add-expense" ${members.length ? '' : 'disabled'}>+ Add Expense</button>
        </div>
        <div class="card">
          <div class="card-header"><h3>Balances</h3></div>
          ${balances?.memberBalances?.length ? balances.memberBalances.map(b => `
            <div class="balance-item">
              <span>${esc(b.userName || b.userId?.substring(0, 8))}</span>
              <span style="font-weight:700;color:${b.netBalance >= 0 ? 'var(--accent-g)' : 'var(--accent)'}">
                ${b.netBalance >= 0 ? '+' : ''}${formatCurrency(b.netBalance)}
              </span>
            </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem">No balance data</p>'}
        </div>
      </div>
      <div class="card activity-card">
        <div class="card-header"><h3>Group Activity</h3></div>
        ${activity?.length ? activity.slice(0, 20).map(a => `
          <div class="activity-item">
            <div class="activity-message">${esc(a.message)}</div>
            <div class="activity-meta">${formatDate(a.createdAt)} · ${esc(activityTypeLabel(a.activityType))}</div>
          </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem">No activity yet</p>'}
      </div>
      <div class="card settlement-card">
        <div class="card-header"><h3>Simplified Settlements</h3></div>
        ${balances?.simplifiedDebts?.length ? balances.simplifiedDebts.map(s => `
          <div class="settlement-row">
            <div>
              <strong>${esc(s.fromUserName)}</strong> owes <strong>${esc(s.toUserName)}</strong>
              <span style="font-weight:800;color:var(--accent);margin-left:6px">${formatCurrency(s.amount)}</span>
            </div>
            <button class="btn btn-secondary btn-sm settle-btn"
              data-from="${s.fromUserId}" data-to="${s.toUserId}"
              data-from-name="${esc(s.fromUserName)}" data-to-name="${esc(s.toUserName)}">
              Record Payment
            </button>
          </div>`).join('') : '<p style="color:var(--text-dim);margin:0">All balances are settled.</p>'}
      </div>`;

    document.getElementById('sp-add-member').onclick = () => addMemberModal(groupId, userId);
    document.getElementById('sp-add-expense').onclick = () => addExpenseModal(groupId, members, userId);
    el.querySelectorAll('.delete-expense-btn').forEach(btn => {
      btn.onclick = async () => {
        if (!confirm('Delete this shared expense? Linked personal transactions will be removed.')) return;
        try {
          await api.delete(`/upsert/groups/${groupId}/expenses/${btn.dataset.id}`);
          toast('Expense deleted', 'success');
          await loadGroupDetail(groupId, userId);
        } catch (e) { toast(e.message, 'error'); }
      };
    });
    el.querySelectorAll('.settle-btn').forEach(btn => {
      btn.onclick = async () => {
        if (!confirm(`Record settlement: did ${btn.dataset.fromName} pay ${btn.dataset.toName}?`)) return;
        try {
          await api.post(`/upsert/groups/${groupId}/settle`, null, { params: { fromUserId: btn.dataset.from, toUserId: btn.dataset.to } });
          toast('Settlement recorded!', 'success');
          await loadGroupDetail(groupId, userId);
        } catch (e) { toast(e.message, 'error'); }
      };
    });
  } catch (err) {
    el.innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`;
  }
}

function createGroupModal(userId) {
  openModal('Create Group', `
    <form id="cg-form">
      <div class="form-group"><label for="cg-name">Group Name</label><input class="form-input" id="cg-name" required maxlength="100"></div>
      <div class="form-group"><label for="cg-desc">Description</label><textarea class="form-textarea" id="cg-desc" maxlength="300"></textarea></div>
      <div class="form-group"><label for="cg-currency">Currency</label><input class="form-input" id="cg-currency" value="INR" maxlength="3" required></div>
      ${modalActions('Cancel', 'Create')}
    </form>`, {
    onSubmit: async () => {
      await api.post('/upsert/groups', {
        name: document.getElementById('cg-name').value.trim(),
        description: document.getElementById('cg-desc').value.trim() || undefined,
        createdBy: userId,
        currency: document.getElementById('cg-currency').value.trim().toUpperCase()
      });
      toast('Group created', 'success');
      loadGroups(userId);
    }
  });
}

function addMemberModal(groupId, userId) {
  openModal('Add Member', `
    <form id="am-form">
      <div class="form-group">
        <label for="am-search">Search by username or email</label>
        <input class="form-input" id="am-search" placeholder="Type to search registered users…" autocomplete="off" required>
        <div id="am-results" class="user-search-results" hidden></div>
        <input type="hidden" id="am-uid">
        <input type="hidden" id="am-name">
        <p id="am-selected" style="font-size:.82rem;color:var(--text-dim);margin-top:8px"></p>
      </div>
      ${modalActions('Cancel', 'Add')}
    </form>`, {
    onSubmit: async () => {
      const memberId = document.getElementById('am-uid').value;
      const memberName = document.getElementById('am-name').value;
      if (!memberId) {
        toast('Select a registered user from the search results', 'error');
        throw new Error('No user selected');
      }
      await api.post(`/upsert/groups/${groupId}/members`, {
        userId: memberId,
        name: memberName
      });
      toast('Member added', 'success');
      loadGroupDetail(groupId, userId);
    }
  });

  const searchInput = document.getElementById('am-search');
  const resultsEl = document.getElementById('am-results');
  const selectedEl = document.getElementById('am-selected');
  let debounceTimer = null;

  searchInput.oninput = () => {
    clearTimeout(debounceTimer);
    document.getElementById('am-uid').value = '';
    document.getElementById('am-name').value = '';
    selectedEl.textContent = '';
    const q = searchInput.value.trim();
    if (q.length < 2) {
      resultsEl.hidden = true;
      resultsEl.innerHTML = '';
      return;
    }
    debounceTimer = setTimeout(async () => {
      try {
        const users = await api.get('/auth/users/search', { q, limit: 8 });
        if (!users.length) {
          resultsEl.innerHTML = '<div class="user-search-empty">No users found</div>';
        } else {
          resultsEl.innerHTML = users.map(u => `
            <button type="button" class="user-search-item" data-id="${esc(u.userId)}" data-name="${esc(u.username)}">
              <strong>@${esc(u.username)}</strong>
              <span>${esc(u.email)}</span>
            </button>`).join('');
          resultsEl.querySelectorAll('.user-search-item').forEach(btn => {
            btn.onclick = () => {
              document.getElementById('am-uid').value = btn.dataset.id;
              document.getElementById('am-name').value = btn.dataset.name;
              searchInput.value = `@${btn.dataset.name}`;
              selectedEl.textContent = `Selected: @${btn.dataset.name}`;
              resultsEl.hidden = true;
            };
          });
        }
        resultsEl.hidden = false;
      } catch (e) {
        resultsEl.innerHTML = `<div class="user-search-empty">${esc(e.message)}</div>`;
        resultsEl.hidden = false;
      }
    }, 300);
  };
}

function splitTypeLabel(type) {
  const map = { EQUAL: 'Equal', PERCENTAGE: 'Percentage', EXACT: 'By amount' };
  return map[(type || 'EQUAL').toUpperCase()] || 'Equal';
}

function activityTypeLabel(type) {
  const map = {
    GROUP_CREATED: 'Group created',
    MEMBER_ADDED: 'Member added',
    EXPENSE_ADDED: 'Expense added',
    EXPENSE_DELETED: 'Expense deleted',
    SETTLEMENT_RECORDED: 'Settlement'
  };
  return map[(type || '').toUpperCase()] || type || 'Activity';
}

function addExpenseModal(groupId, members, userId) {
  if (!members.length) {
    toast('Add at least one member before creating an expense', 'error');
    return;
  }
  openModal('Add Shared Expense', `
    <form id="ae-form">
      <div class="form-group"><label for="ae-desc">Description</label><input class="form-input" id="ae-desc" required maxlength="100"></div>
      <div class="form-row">
        <div class="form-group"><label for="ae-amt">Amount</label><input class="form-input" id="ae-amt" type="number" step="0.01" min="0.01" required></div>
        <div class="form-group"><label for="ae-paid">Paid By</label>
          <select class="form-select" id="ae-paid" required>
            ${members.map(m => `<option value="${m.userId}">@${esc(m.name)}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="ae-currency">Currency</label><input class="form-input" id="ae-currency" value="INR" maxlength="3" required></div>
        <div class="form-group">
          <label for="ae-split-type">Split Type</label>
          <select class="form-select" id="ae-split-type">
            <option value="EQUAL">Split equally</option>
            <option value="PERCENTAGE">Split by percentage</option>
            <option value="EXACT">Split by exact amount</option>
          </select>
        </div>
      </div>
      <div id="ae-split-details" hidden></div>
      ${modalActions('Cancel', 'Add')}
    </form>`, {
    onSubmit: async () => {
      const splitType = document.getElementById('ae-split-type').value;
      const amount = parseFloat(document.getElementById('ae-amt').value);
      const payload = {
        description: document.getElementById('ae-desc').value.trim(),
        amount,
        paidBy: document.getElementById('ae-paid').value,
        currency: document.getElementById('ae-currency').value.trim().toUpperCase(),
        splitType
      };

      if (splitType === 'PERCENTAGE' || splitType === 'EXACT') {
        const splitDetails = members.map(m => ({
          userId: m.userId,
          userName: m.name,
          value: parseFloat(document.getElementById(`ae-split-${m.userId}`).value) || 0
        }));
        const total = splitDetails.reduce((sum, d) => sum + d.value, 0);
        if (splitType === 'PERCENTAGE' && Math.abs(total - 100) > 0.01) {
          toast('Percentages must add up to 100%', 'error');
          throw new Error('Invalid percentage split');
        }
        if (splitType === 'EXACT' && Math.abs(total - amount) > 0.01) {
          toast(`Exact amounts must add up to ${amount}`, 'error');
          throw new Error('Invalid exact split');
        }
        payload.splitDetails = splitDetails;
      }

      await api.post(`/upsert/groups/${groupId}/expenses`, payload);
      toast('Expense added', 'success');
      loadGroupDetail(groupId, userId);
    }
  });

  const splitTypeSelect = document.getElementById('ae-split-type');
  const splitDetailsEl = document.getElementById('ae-split-details');
  const amountInput = document.getElementById('ae-amt');

  function defaultSplitValue(type, amount) {
    if (!members.length) return 0;
    if (type === 'PERCENTAGE') return Math.round((100 / members.length) * 100) / 100;
    return Math.round((amount / members.length) * 100) / 100;
  }

  function updateSplitSummary() {
    const summary = document.getElementById('ae-split-summary');
    if (!summary) return;
    const type = splitTypeSelect.value;
    const total = members.reduce((sum, m) => {
      const el = document.getElementById(`ae-split-${m.userId}`);
      return sum + (parseFloat(el?.value) || 0);
    }, 0);
    const amount = parseFloat(amountInput.value) || 0;
    if (type === 'PERCENTAGE') {
      const ok = Math.abs(total - 100) <= 0.01;
      summary.textContent = `Total: ${total.toFixed(2)}% ${ok ? '' : '(must equal 100%)'}`;
      summary.style.color = ok ? 'var(--accent-g)' : 'var(--accent)';
    } else {
      const ok = Math.abs(total - amount) <= 0.01;
      summary.textContent = `Total: ${formatCurrency(total)} ${ok ? '' : `(must equal ${formatCurrency(amount)})`}`;
      summary.style.color = ok ? 'var(--accent-g)' : 'var(--accent)';
    }
  }

  function renderSplitDetails() {
    const type = splitTypeSelect.value;
    if (type === 'EQUAL') {
      splitDetailsEl.hidden = true;
      splitDetailsEl.innerHTML = '';
      return;
    }

    const amount = parseFloat(amountInput.value) || 0;
    const defaultVal = defaultSplitValue(type, amount);
    const isPct = type === 'PERCENTAGE';

    splitDetailsEl.hidden = false;
    splitDetailsEl.innerHTML = `
      <div class="form-group split-details-panel">
        <label>${isPct ? 'Percentage per member' : 'Amount per member'}</label>
        ${members.map(m => `
          <div class="split-detail-row">
            <span>@${esc(m.name)}</span>
            <div class="split-detail-input-wrap">
              <input class="form-input split-detail-input" id="ae-split-${m.userId}"
                type="number" step="${isPct ? '0.01' : '0.01'}" min="0"
                ${isPct ? 'max="100"' : ''} value="${defaultVal}" required>
              ${isPct ? '<span class="split-detail-suffix">%</span>' : ''}
            </div>
          </div>`).join('')}
        <p id="ae-split-summary" class="split-summary"></p>
      </div>`;

    members.forEach(m => {
      document.getElementById(`ae-split-${m.userId}`).oninput = updateSplitSummary;
    });
    updateSplitSummary();
  }

  splitTypeSelect.onchange = renderSplitDetails;
  amountInput.oninput = () => {
    if (splitTypeSelect.value === 'EXACT') updateSplitSummary();
  };
}
