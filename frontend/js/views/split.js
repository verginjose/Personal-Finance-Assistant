import { api, Auth, toast } from '../utils/api.js';

let currentGroupId = null;

export async function renderSplit(container) {
  currentGroupId = null;
  const userId = Auth.getUserId();
  container.innerHTML = `
    <div class="page-header fade-up"><h1>Split Expenses</h1><p>Manage shared expenses with groups</p></div>
    <div class="toolbar fade-up" style="animation-delay:.05s">
      <button class="btn btn-primary btn-sm" id="sp-create">+ New Group</button>
      <button class="btn btn-secondary btn-sm" id="sp-back" style="display:none">← Back to Groups</button>
    </div>
    <div id="sp-content" class="fade-up" style="animation-delay:.1s"></div>`;

  document.getElementById('sp-create').onclick = () => createGroupModal(userId);
  document.getElementById('sp-back').onclick = () => { currentGroupId = null; loadGroups(userId); };
  loadGroups(userId);
}

async function loadGroups(userId) {
  const el = document.getElementById('sp-content');
  document.getElementById('sp-back').style.display = 'none';
  document.getElementById('sp-create').style.display = '';
  try {
    const groups = await api.get('/upsert/groups', { userId });
    if (!groups.length) {
      el.innerHTML = '<div class="empty-state"><div class="empty-icon">👥</div><p>No groups yet. Create one to start splitting!</p></div>';
      return;
    }
    el.innerHTML = `<div class="card-grid card-grid-3">${groups.map(g => `
      <div class="card group-card" data-id="${g.id}">
        <div class="group-name">${esc(g.name)}</div>
        <div class="group-meta">${g.description || 'No description'}</div>
      </div>`).join('')}</div>`;
    el.querySelectorAll('.group-card').forEach(c => c.onclick = () => loadGroupDetail(+c.dataset.id, userId));
  } catch (err) { el.innerHTML = `<p style="color:var(--accent)">${err.message}</p>`; }
}

async function loadGroupDetail(groupId, userId) {
  currentGroupId = groupId;
  const el = document.getElementById('sp-content');
  document.getElementById('sp-back').style.display = '';
  document.getElementById('sp-create').style.display = 'none';
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
        <p style="color:var(--text-dim);margin-top:4px">${esc(group.description||'')}</p>
      </div>
      <div class="card-grid card-grid-3">
        <div class="card">
          <h4 style="margin-bottom:12px">Members (${members.length})</h4>
          ${members.map(m => `<div style="padding:6px 0;border-bottom:1px solid var(--border);font-size:.88rem">${esc(m.name)} <span style="color:var(--text-dim)">${m.userId?.substring(0,8)||''}</span></div>`).join('')}
          <button class="btn btn-secondary btn-sm" style="margin-top:12px;width:100%;justify-content:center" id="sp-add-member">+ Add Member</button>
        </div>
        <div class="card">
          <h4 style="margin-bottom:12px">Expenses (${expenses.length})</h4>
          ${expenses.length ? expenses.slice(0,8).map(e => `<div style="display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid var(--border);font-size:.88rem"><span>${esc(e.description||'Expense')}</span><span style="font-weight:600;color:var(--accent)">₹${Number(e.amount).toLocaleString()}</span></div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem">No expenses yet</p>'}
          <button class="btn btn-primary btn-sm" style="margin-top:12px;width:100%;justify-content:center" id="sp-add-expense">+ Add Expense</button>
        </div>
        <div class="card">
          <h4 style="margin-bottom:12px">Balances</h4>
          ${balances?.balances?.length ? balances.balances.map(b => `<div class="balance-item"><span>${esc(b.memberName||b.userId?.substring(0,8))}</span><span style="font-weight:700;color:${b.balance>=0?'var(--accent-g)':'var(--accent)'}">${b.balance>=0?'+':''}₹${Number(b.balance).toLocaleString()}</span></div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem">No balance data</p>'}
        </div>
      </div>`;
    document.getElementById('sp-add-member').onclick = () => addMemberModal(groupId, userId);
    document.getElementById('sp-add-expense').onclick = () => addExpenseModal(groupId, members, userId);
  } catch (err) { el.innerHTML = `<p style="color:var(--accent)">${err.message}</p>`; }
}

function createGroupModal(userId) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `<div class="modal"><h2>Create Group</h2><form id="cg-form">
    <div class="form-group"><label>Group Name</label><input class="form-input" id="cg-name" required></div>
    <div class="form-group"><label>Description</label><textarea class="form-textarea" id="cg-desc"></textarea></div>
    <div class="modal-actions"><button type="button" class="btn btn-secondary" id="cg-cancel">Cancel</button><button type="submit" class="btn btn-primary">Create</button></div>
  </form></div>`;
  document.body.appendChild(overlay);
  overlay.onclick = e => { if (e.target === overlay) overlay.remove(); };
  document.getElementById('cg-cancel').onclick = () => overlay.remove();
  document.getElementById('cg-form').onsubmit = async e => {
    e.preventDefault();
    try {
      await api.post('/upsert/groups', { name: document.getElementById('cg-name').value, description: document.getElementById('cg-desc').value, createdByUserId: userId });
      toast('Group created', 'success'); overlay.remove(); loadGroups(userId);
    } catch (err) { toast(err.message, 'error'); }
  };
}

function addMemberModal(groupId, userId) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `<div class="modal"><h2>Add Member</h2><form id="am-form">
    <div class="form-group"><label>Member Name</label><input class="form-input" id="am-name" required></div>
    <div class="form-group"><label>User ID (UUID)</label><input class="form-input" id="am-uid" placeholder="Optional"></div>
    <div class="modal-actions"><button type="button" class="btn btn-secondary" id="am-cancel">Cancel</button><button type="submit" class="btn btn-primary">Add</button></div>
  </form></div>`;
  document.body.appendChild(overlay);
  overlay.onclick = e => { if (e.target === overlay) overlay.remove(); };
  document.getElementById('am-cancel').onclick = () => overlay.remove();
  document.getElementById('am-form').onsubmit = async e => {
    e.preventDefault();
    try {
      await api.post(`/upsert/groups/${groupId}/members`, { name: document.getElementById('am-name').value, userId: document.getElementById('am-uid').value || null });
      toast('Member added', 'success'); overlay.remove(); loadGroupDetail(groupId, userId);
    } catch (err) { toast(err.message, 'error'); }
  };
}

function addExpenseModal(groupId, members, userId) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `<div class="modal"><h2>Add Shared Expense</h2><form id="ae-form">
    <div class="form-group"><label>Description</label><input class="form-input" id="ae-desc" required></div>
    <div class="form-row">
      <div class="form-group"><label>Amount</label><input class="form-input" id="ae-amt" type="number" step="0.01" required></div>
      <div class="form-group"><label>Paid By</label><select class="form-select" id="ae-paid">${members.map(m => `<option value="${m.id}">${esc(m.name)}</option>`).join('')}</select></div>
    </div>
    <div class="modal-actions"><button type="button" class="btn btn-secondary" id="ae-cancel">Cancel</button><button type="submit" class="btn btn-primary">Add</button></div>
  </form></div>`;
  document.body.appendChild(overlay);
  overlay.onclick = e => { if (e.target === overlay) overlay.remove(); };
  document.getElementById('ae-cancel').onclick = () => overlay.remove();
  document.getElementById('ae-form').onsubmit = async e => {
    e.preventDefault();
    try {
      await api.post(`/upsert/groups/${groupId}/expenses`, { description: document.getElementById('ae-desc').value, amount: parseFloat(document.getElementById('ae-amt').value), paidByMemberId: +document.getElementById('ae-paid').value, groupId });
      toast('Expense added', 'success'); overlay.remove(); loadGroupDetail(groupId, userId);
    } catch (err) { toast(err.message, 'error'); }
  };
}

function esc(s) { const d = document.createElement('div'); d.textContent = s || ''; return d.innerHTML; }
