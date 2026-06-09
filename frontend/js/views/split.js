import { api, Auth, toast } from '../utils/api.js';
import { esc, pageHeader, emptyState, formatCurrency, openModal, modalActions } from '../utils/ui.js';

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
    const [group, members, expenses, balances] = await Promise.all([
      api.get(`/upsert/groups/${groupId}`),
      api.get(`/upsert/groups/${groupId}/members`),
      api.get(`/upsert/groups/${groupId}/expenses`),
      api.get(`/upsert/groups/${groupId}/balances`)
    ]);
    el.innerHTML = `
      <div class="card" style="margin-bottom:20px">
        <h3>${esc(group.name)}</h3>
        <p style="color:var(--text-dim);margin-top:4px">${esc(group.description || '')}</p>
      </div>
      <div class="card-grid card-grid-3">
        <div class="card">
          <div class="card-header"><h3>Members (${members.length})</h3></div>
          ${members.map(m => `<div class="balance-item"><span>${esc(m.name)}</span><span style="color:var(--text-dim);font-size:.78rem">${esc(m.userId?.substring(0, 8) || '')}</span></div>`).join('')}
          <button class="btn btn-secondary btn-sm" style="margin-top:12px;width:100%" id="sp-add-member">+ Add Member</button>
        </div>
        <div class="card">
          <div class="card-header"><h3>Expenses (${expenses.length})</h3></div>
          ${expenses.length ? expenses.slice(0, 8).map(e => `
            <div class="balance-item">
              <span>${esc(e.description || 'Expense')}</span>
              <span style="font-weight:600;color:var(--accent)">${formatCurrency(e.amount)}</span>
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

function resolveMemberUserId(input) {
  const trimmed = (input || '').trim();
  if (trimmed) return trimmed;
  return crypto.randomUUID();
}

function addMemberModal(groupId, userId) {
  openModal('Add Member', `
    <form id="am-form">
      <div class="form-group"><label for="am-name">Member Name</label><input class="form-input" id="am-name" required maxlength="150"></div>
      <div class="form-group">
        <label for="am-uid">User ID</label>
        <input class="form-input" id="am-uid" placeholder="UUID of registered user (optional)">
        <p style="font-size:.78rem;color:var(--text-muted);margin-top:6px">Leave blank for a guest member — a temporary ID will be assigned.</p>
      </div>
      ${modalActions('Cancel', 'Add')}
    </form>`, {
    onSubmit: async () => {
      await api.post(`/upsert/groups/${groupId}/members`, {
        name: document.getElementById('am-name').value.trim(),
        userId: resolveMemberUserId(document.getElementById('am-uid').value)
      });
      toast('Member added', 'success');
      loadGroupDetail(groupId, userId);
    }
  });
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
            ${members.map(m => `<option value="${m.userId}">${esc(m.name)}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="form-group"><label for="ae-currency">Currency</label><input class="form-input" id="ae-currency" value="INR" maxlength="3" required></div>
      ${modalActions('Cancel', 'Add')}
    </form>`, {
    onSubmit: async () => {
      await api.post(`/upsert/groups/${groupId}/expenses`, {
        description: document.getElementById('ae-desc').value.trim(),
        amount: parseFloat(document.getElementById('ae-amt').value),
        paidBy: document.getElementById('ae-paid').value,
        currency: document.getElementById('ae-currency').value.trim().toUpperCase(),
        splitType: 'EQUAL'
      });
      toast('Expense added', 'success');
      loadGroupDetail(groupId, userId);
    }
  });
}
