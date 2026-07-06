import { api, Auth, toast } from '../utils/api.js?v=2026070603';
import { esc, pageHeader, emptyState, formatCurrency, formatDate, openModal, confirmModal, modalActions, avatar } from '../utils/ui.js?v=2026070603';
import { icon } from '../utils/icons.js?v=2026070603';

let currentGroupId = null;
let expensesPage = 0, expensesTotal = 1;
let activityPage = 0, activityTotal = 1;

// Global notification listeners for Split view
window.addEventListener('group-invite', () => {
  const userId = Auth.getUserId();
  if (userId && !currentGroupId && document.getElementById('sp-content')) {
    loadGroups(userId); // Refresh group list if on the main split view
  }
});

window.addEventListener('expense-added', (e) => {
  const userId = Auth.getUserId();
  const data = e.detail;
  if (userId && currentGroupId && data.groupId === currentGroupId && document.getElementById('sp-content')) {
    loadGroupDetail(currentGroupId, userId); // Refresh group details
  }
});

window.addEventListener('debt-settled', (e) => {
  const userId = Auth.getUserId();
  const data = e.detail;
  if (userId && currentGroupId && data.groupId === currentGroupId && document.getElementById('sp-content')) {
    loadGroupDetail(currentGroupId, userId); // Refresh group details
  }
});

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
  
  const pendingScanRaw = localStorage.getItem('pfa_pending_scan');
  if (pendingScanRaw) {
    try {
      const pendingScan = JSON.parse(pendingScanRaw);
      localStorage.removeItem('pfa_pending_scan');
      window._pfaPendingScan = pendingScan;
      loadGroupDetail(pendingScan.groupId, userId);
      return;
    } catch(e) {}
  }
  
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
    const activeGroups = groups.filter(g => !g.isArchived);
    const archivedGroups = groups.filter(g => g.isArchived);

    let html = '';

    const renderCard = (g) => {
      const isPending = g.currentUserStatus === 'PENDING';
      let balHtml = '';
      if (g.currentUserNetBalance > 0) {
        balHtml = `<div style="margin-top:8px; font-size:0.85rem; font-weight:600; color:var(--accent-g);">You get back ${formatCurrency(g.currentUserNetBalance, g.currency)}</div>`;
      } else if (g.currentUserNetBalance < 0) {
        balHtml = `<div style="margin-top:8px; font-size:0.85rem; font-weight:600; color:var(--accent);">You owe ${formatCurrency(Math.abs(g.currentUserNetBalance), g.currency)}</div>`;
      }
      return `
      <div class="card group-card" data-id="${g.id}" role="button" tabindex="0" ${isPending ? 'style="border: 2px solid var(--accent-y);"' : ''}>
        <div class="group-name">${esc(g.name)}</div>
        <div class="group-meta">${esc(g.description || 'No description')}</div>
        ${balHtml}
        ${isPending ? `
          <div style="margin-top: 12px; display:flex; gap: 8px;" class="sp-pending-actions">
            <button class="btn btn-primary btn-sm sp-outside-accept" data-id="${g.id}">Accept</button>
            <button class="btn btn-secondary btn-sm sp-outside-decline" data-id="${g.id}">Decline</button>
          </div>
        ` : ''}
      </div>`;
    };

    if (activeGroups.length > 0) {
      html += `<div class="card-grid card-grid-3">${activeGroups.map(renderCard).join('')}</div>`;
    } else if (archivedGroups.length === 0) {
      html += `<div style="text-align:center; padding: 40px; color: var(--text-dim);">No active groups.</div>`;
    }

    if (archivedGroups.length > 0) {
      html += `
        <div style="margin-top: 40px; border-top: 1px solid var(--border-light); padding-top: 20px;">
          <div style="display:flex; align-items:center; gap:8px; cursor:pointer; color:var(--text-dim); padding:12px; background:var(--bg-card-h); border-radius:8px; width:max-content; transition: background 0.2s;" onmouseover="this.style.background='var(--bg-elevated)'" onmouseout="this.style.background='var(--bg-card-h)'" onclick="const c = document.getElementById('archived-groups-container'); c.style.display = c.style.display === 'none' ? 'block' : 'none';">
            ${icon('archive', 18)} <span style="font-weight: 500;">Archived Groups (${archivedGroups.length})</span>
          </div>
          <div id="archived-groups-container" style="display:none; margin-top:20px;">
            <div class="card-grid card-grid-3" style="opacity: 0.7;">
              ${archivedGroups.map(renderCard).join('')}
            </div>
          </div>
        </div>
      `;
    }

    el.innerHTML = html;

    el.querySelectorAll('.sp-outside-accept').forEach(btn => {
      btn.onclick = async (e) => {
        e.stopPropagation();
        try {
          await api.post(`/upsert/groups/${btn.dataset.id}/members/${userId}/accept`);
          toast('Invitation accepted!', 'success');
          loadGroups(userId);
        } catch(err) { toast(err.message, 'error'); }
      };
    });

    el.querySelectorAll('.sp-outside-decline').forEach(btn => {
      btn.onclick = async (e) => {
        e.stopPropagation();
        if (!(await confirmModal('Decline', 'Are you sure you want to decline this invitation?', 'Decline'))) return;
        try {
          await api.post(`/upsert/groups/${btn.dataset.id}/members/${userId}/reject`);
          toast('Invitation declined', 'info');
          loadGroups(userId);
        } catch(err) { toast(err.message, 'error'); }
      };
    });

    el.querySelectorAll('.group-card').forEach(c => {
      const open = () => loadGroupDetail(+c.dataset.id, userId);
      c.onclick = (e) => { if(!e.target.closest('.sp-pending-actions')) open(); };
      c.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } };
    });
  } catch (err) {
    el.innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`;
  }
}

async function loadGroupDetail(groupId, userId) {
  currentGroupId = groupId;
  expensesPage = 0;
  activityPage = 0;
  const el = document.getElementById('sp-content');
  document.getElementById('sp-back').style.display = '';
  document.getElementById('sp-create').style.display = 'none';
  el.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
  try {
    const activityPromise = api.get(`/upsert/groups/${groupId}/activity`, { page: activityPage, size: 8 });
    const [group, membersRaw, expensesResult, balances] = await Promise.all([
      api.get(`/upsert/groups/${groupId}`),
      api.get(`/upsert/groups/${groupId}/members`),
      api.get(`/upsert/groups/${groupId}/expenses`, { page: expensesPage, size: 8 }),
      api.get(`/upsert/groups/${groupId}/balances`)
    ]);

    expensesTotal = expensesResult.totalPages || 1;
    const expenses = expensesResult.content || [];

    let usersMap = {};
    try {
      const userIds = membersRaw.map(m => m.userId);
      if (userIds.length > 0) {
        const users = await api.post('/auth/users/bulk', userIds);
        users.forEach(u => { usersMap[u.userId] = u; });
      }
    } catch(e) { console.warn('Failed to fetch user profiles', e); }

    const members = membersRaw.map(m => {
      const u = usersMap[m.userId] || {};
      return { ...m, profilePicture: u.profilePicture, email: u.email, username: u.username };
    });

    let pendingScan = null;
    if (window._pfaPendingScan && String(window._pfaPendingScan.groupId) === String(groupId)) {
       pendingScan = window._pfaPendingScan;
       delete window._pfaPendingScan;
       const eligibleMembers = members.filter(m => m.status === 'ACCEPTED');
       // Open modal immediately while background loads
       try { addExpenseModal(groupId, eligibleMembers, userId, pendingScan); } catch (err) {}
    }
    const currentUserMember = members.find(m => m.userId === userId);
    const isPending = currentUserMember && currentUserMember.status === 'PENDING';

    if (isPending) {
      el.innerHTML = `
        <div class="card" style="margin-bottom:20px; text-align:center; padding:40px;">
          <h2 style="margin-bottom:8px">You've been invited!</h2>
          <p style="color:var(--text-dim); margin-bottom:24px">You have been invited to join the group <strong>${esc(group.name)}</strong>.</p>
          <div style="display:flex; justify-content:center; gap:16px;">
            <button class="btn btn-secondary" id="sp-decline-invite">Decline</button>
            <button class="btn btn-primary" id="sp-accept-invite">Accept Invitation</button>
          </div>
        </div>`;
        
      document.getElementById('sp-accept-invite').onclick = async () => {
        try {
          await api.post(`/upsert/groups/${groupId}/members/${userId}/accept`);
          toast('Invitation accepted!', 'success');
          loadGroupDetail(groupId, userId);
        } catch(e) { toast(e.message, 'error'); }
      };
      document.getElementById('sp-decline-invite').onclick = async () => {
        if (!(await confirmModal('Decline', 'Are you sure you want to decline this invitation?', 'Decline'))) return;
        try {
          await api.post(`/upsert/groups/${groupId}/members/${userId}/reject`);
          toast('Invitation declined', 'info');
          document.getElementById('sp-back').click();
        } catch(e) { toast(e.message, 'error'); }
      };
      return;
    }
    
    const resolveMemberNameLocally = (id) => {
      const m = members.find(x => x.userId === id);
      return m ? m.name : id.substring(0, 8);
    };

    const resolveMemberAvatarLocally = (id) => {
      const m = members.find(x => x.userId === id);
      return m ? m.profilePicture : null;
    };

    const myBalanceObj = balances?.memberBalances?.find(b => b.userId === userId);
    const myNetBalance = myBalanceObj ? myBalanceObj.netBalance : 0;
    const eligibleMembers = members.filter(m => m.status === 'ACCEPTED');

    // Build balance pills for members
    const myBalancePill = myNetBalance !== 0
      ? `<span class="group-balance-pill" style="background:rgba(${myNetBalance > 0 ? '34,201,147' : '249,115,22'},0.12); color:${myNetBalance > 0 ? 'var(--accent-g)' : 'var(--accent)'}; border:1px solid ${myNetBalance > 0 ? 'rgba(34,201,147,0.3)' : 'rgba(249,115,22,0.3)'};">
          ${myNetBalance > 0 ? '▲ Gets back ' + formatCurrency(myNetBalance) : '▼ Owes ' + formatCurrency(Math.abs(myNetBalance))}
        </span>`
      : '';

    el.innerHTML = `
      <!-- Compact group header -->
      <div class="group-compact-header fade-up">
        <div class="group-compact-header-left">
          <div class="group-compact-title">${esc(group.name)}</div>
          <div class="group-compact-meta">
            <button class="group-compact-members" id="sp-view-members">
              ${icon('users', 'sm')} ${members.length} Member${members.length !== 1 ? 's' : ''}
            </button>
            ${myBalancePill}
          </div>
        </div>
        <div class="group-compact-actions">
          <button class="btn-add-expense" id="sp-add-expense" ${eligibleMembers.length ? '' : 'disabled'}>
            ${icon('plus', 'sm')} Add Expense
          </button>
          <button class="btn-group-menu" id="sp-group-menu-btn" title="Group options">
            ${icon('settings', 'sm')}
          </button>
        </div>
      </div>

      <!-- Group options dropdown (hidden by default) -->
      <div id="sp-group-menu" style="display:none; background:var(--bg-elevated); border:1px solid var(--border); border-radius:var(--radius-sm); padding:6px; box-shadow:var(--shadow); margin-bottom:12px; animation: fadeIn 0.15s ease both;">
        <button class="btn btn-secondary btn-sm" id="sp-edit-group" style="width:100%; justify-content:flex-start; text-align:left; margin-bottom:4px;">${icon('edit', 'sm')} Edit Group</button>
        <button class="btn btn-secondary btn-sm" id="sp-archive-group" style="width:100%; justify-content:flex-start; text-align:left; background:var(--bg-card-h); margin-bottom:4px;">${group.isArchived ? icon('arrow-up', 'sm') + ' Unarchive' : icon('arrow-down', 'sm') + ' Archive'}</button>
        <button class="btn btn-secondary btn-sm" id="sp-leave-group" style="width:100%; justify-content:flex-start; text-align:left; margin-bottom:4px;">${icon('log-out', 'sm')} Leave Group</button>
        <button class="btn btn-sm" id="sp-delete-group" style="width:100%; justify-content:flex-start; text-align:left; background:rgba(239,68,68,0.1); color:var(--accent); border:1px solid rgba(239,68,68,0.2);">${icon('trash', 'sm')} Delete Group</button>
      </div>

      <!-- Split pane: vertical nav + content -->
      <div class="split-pane fade-up" style="margin-top: 20px;">
        <!-- Left vertical nav -->
        <nav class="split-pane-nav">
          <button class="split-nav-btn active" data-panel="expenses">
            ${icon('receipt', 'sm')} Expenses
          </button>
          <button class="split-nav-btn" data-panel="balances">
            ${icon('bar-chart', 'sm')} Balances
          </button>
          <button class="split-nav-btn" data-panel="activity">
            ${icon('list', 'sm')} Activity
          </button>
        </nav>

        <!-- Right content area -->
        <div class="split-pane-content">
          <!-- Expenses panel -->
          <div class="split-pane-panel active" id="panel-expenses">
            <div id="expenses-list"></div>
            <div id="expenses-pagination" class="pagination" style="margin-top:16px;"></div>
          </div>

          <!-- Balances panel -->
          <div class="split-pane-panel" id="panel-balances">
            <div style="margin-bottom:20px;">
              <h4 style="margin-bottom:12px; color:var(--text-muted); text-transform:uppercase; font-size:0.75rem; letter-spacing:0.05em;">Net Balances</h4>
              <div style="display:grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap:10px;">
                ${balances?.memberBalances?.length ? balances.memberBalances.map(b => `
                  <div style="background:var(--bg-card-h); padding:12px 14px; border-radius:var(--radius-sm); border:1px solid var(--border); display:flex; justify-content:space-between; align-items:center; gap:10px;">
                    <div style="display:flex; align-items:center; gap:7px; overflow:hidden; min-width:0;">
                      ${avatar(b.userName || b.userId, 22, resolveMemberAvatarLocally(b.userId))}
                      <span style="color:var(--text-dim); font-size:0.82rem; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">@${esc(b.userName || b.userId?.substring(0, 8))}</span>
                    </div>
                    <span style="font-weight:700; color:${b.netBalance >= 0 ? 'var(--accent-g)' : 'var(--accent)'}; font-size:1rem; flex-shrink:0;">
                      ${b.netBalance >= 0 ? '+' : ''}${formatCurrency(b.netBalance)}
                    </span>
                  </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem;">No balance data yet.</p>'}
              </div>
            </div>

            <div>
              <h4 style="margin-bottom:12px; color:var(--text-muted); text-transform:uppercase; font-size:0.75rem; letter-spacing:0.05em;">Who Owes Who</h4>
              ${balances?.simplifiedDebts?.length ? balances.simplifiedDebts.map(s => {
                let text = '', amountColor = 'var(--text)', amountSign = '';
                if (s.fromUserId === userId) {
                  text = `You owe ${avatar(s.toUserName, 20, resolveMemberAvatarLocally(s.toUserId))} <strong>${esc(s.toUserName)}</strong>`;
                  amountColor = 'var(--accent)'; amountSign = '-';
                } else if (s.toUserId === userId) {
                  text = `${avatar(s.fromUserName, 20, resolveMemberAvatarLocally(s.fromUserId))} <strong>${esc(s.fromUserName)}</strong> owes you`;
                  amountColor = 'var(--accent-g)'; amountSign = '+';
                } else {
                  text = `${avatar(s.fromUserName, 20, resolveMemberAvatarLocally(s.fromUserId))} <strong>${esc(s.fromUserName)}</strong> → ${avatar(s.toUserName, 20, resolveMemberAvatarLocally(s.toUserId))} <strong>${esc(s.toUserName)}</strong>`;
                }
                return `
                <div style="display:flex; justify-content:space-between; align-items:center; padding:14px 0; border-bottom:1px solid var(--border-light); gap:14px; flex-wrap:wrap;">
                  <div style="font-size:0.92rem; flex:1 1 auto; min-width:180px; word-break:break-word;">${text}</div>
                  <div style="display:flex; align-items:center; gap:12px; flex-shrink:0;">
                    <span style="font-weight:800; color:${amountColor}; font-size:1rem;">${amountSign}${formatCurrency(s.amount)}</span>
                    <button class="btn btn-secondary btn-sm settle-btn"
                      data-from="${s.fromUserId}" data-to="${s.toUserId}"
                      data-from-name="${esc(s.fromUserName)}" data-to-name="${esc(s.toUserName)}">
                      Record Payment
                    </button>
                  </div>
                </div>`;
              }).join('') : '<p style="color:var(--text-dim); text-align:center; padding:24px 0;">' + icon('check-circle', 'sm') + ' All balances are settled!</p>'}
            </div>
          </div>

          <!-- Activity panel -->
          <div class="split-pane-panel" id="panel-activity">
            <div id="activity-list"></div>
            <div id="activity-pagination" class="pagination" style="margin-top:16px;"></div>
          </div>
        </div>
      </div>`;

    document.getElementById('sp-view-members').onclick = () => viewMembersModal(members, groupId, userId);

    // Group menu toggle
    document.getElementById('sp-group-menu-btn').onclick = (e) => {
      e.stopPropagation();
      const menu = document.getElementById('sp-group-menu');
      menu.style.display = menu.style.display === 'none' ? 'block' : 'none';
    };
    document.addEventListener('click', (e) => {
      const menu = document.getElementById('sp-group-menu');
      if (menu && !e.target.closest('#sp-group-menu') && !e.target.closest('#sp-group-menu-btn')) {
        menu.style.display = 'none';
      }
    }, { once: false });

    // Vertical split-pane nav switching
    document.querySelectorAll('.split-nav-btn').forEach(btn => {
      btn.onclick = () => {
        document.querySelectorAll('.split-nav-btn').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.split-pane-panel').forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        document.getElementById(`panel-${btn.dataset.panel}`)?.classList.add('active');
      };
    });

    document.getElementById('sp-add-expense').onclick = () => addExpenseModal(groupId, eligibleMembers, userId);


    const renderExpenses = (exps) => {
      const elList = document.getElementById('expenses-list');
      if (!elList) return;
      elList.innerHTML = exps.length ? exps.map(e => {
        const expData = { ...e, paidByName: resolveMemberNameLocally(e.paidBy) };
        return `
        <div class="balance-item expense-row" data-exp='${esc(JSON.stringify(expData))}' style="padding:16px 0; border-bottom:1px solid var(--border-light); display:flex; justify-content:space-between; align-items:center; cursor:pointer;">
          <div>
             <div style="font-weight:600; font-size:1.05rem;">${esc(e.description || 'Expense')}</div>
             <div style="margin-top:4px; font-size:0.85rem; color:var(--text-dim);" class="desktop-only">Paid by ${avatar(resolveMemberNameLocally(e.paidBy), 16, resolveMemberAvatarLocally(e.paidBy))} @${esc(resolveMemberNameLocally(e.paidBy))} · <span class="badge badge-info" style="font-size:.68rem;">${esc(splitTypeLabel(e.splitType))}</span>${e.receiptUrl ? ` · <div class="badge badge-info" style="font-size:.68rem;padding: 2px 4px; cursor:pointer;" title="View receipt" onclick="event.stopPropagation(); window.openReceiptLightbox('${esc(e.receiptUrl)}')">${icon('document', 'xs')}</div>` : ''}</div>
          </div>
          <div class="expense-row-actions" style="display:flex; align-items:center; gap: 12px;">
            <span style="font-weight:700;color:var(--text);font-size:1.1rem;">${formatCurrency(e.amount)}</span>
            <button type="button" class="btn btn-danger btn-sm delete-expense-btn" data-id="${e.id}" title="Delete expense" style="padding:6px 10px;">×</button>
          </div>
        </div>`;
      }).join('') : emptyState('receipt', 'No expenses yet', 'Keep track of shared bills and tabs by adding your first expense.');

      elList.querySelectorAll('.expense-row').forEach(row => {
        row.onclick = () => {
          if (window.innerWidth > 768) return;
          const exp = JSON.parse(row.dataset.exp);
          openMobileExpenseModal(exp, groupId, userId);
        };
      });

      elList.querySelectorAll('.delete-expense-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          e.stopPropagation();
          const expenseId = e.currentTarget.dataset.id;
          if (await confirmModal('Delete Expense', 'Are you sure you want to delete this expense? This action cannot be undone.', 'Delete')) {
            try {
              await api.delete(`/upsert/groups/${groupId}/expenses/${expenseId}`, { userId });
              toast('Expense deleted', 'success');
              loadGroupDetail(groupId, userId);
            } catch (err) { toast(err.message, 'error'); }
          }
        });
      });
      renderExpensesPagination();
    };

    const loadGroupExpenses = async () => {
      try {
        const res = await api.get(`/upsert/groups/${groupId}/expenses`, { page: expensesPage, size: 8 });
        expensesTotal = res.totalPages || 1;
        renderExpenses(res.content || []);
      } catch (err) { toast(err.message, 'error'); }
    };

    const renderExpensesPagination = () => {
      const el = document.getElementById('expenses-pagination');
      if (!el || expensesTotal <= 1) { if (el) el.innerHTML = ''; return; }
      
      let html = `<button class="btn btn-secondary btn-icon" id="ep-prev" ${expensesPage === 0 ? 'disabled' : ''} aria-label="Previous Page">${icon('chevron-left', 'sm')}</button>`;
      
      let startPage = Math.max(0, expensesPage - 2);
      let endPage = Math.min(expensesTotal - 1, startPage + 4);
      if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
      
      for (let i = startPage; i <= endPage; i++) {
        html += `<button class="${i === expensesPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`;
      }
      
      html += `<button class="btn btn-secondary btn-icon" id="ep-next" ${expensesPage === expensesTotal - 1 ? 'disabled' : ''} aria-label="Next Page">${icon('chevron-right', 'sm')}</button>`;
      
      el.innerHTML = html;
      
      if (expensesPage > 0) {
        document.getElementById('ep-prev').onclick = () => { expensesPage--; loadGroupExpenses(); };
      }
      if (expensesPage < expensesTotal - 1) {
        document.getElementById('ep-next').onclick = () => { expensesPage++; loadGroupExpenses(); };
      }
      
      el.querySelectorAll('button[data-p]').forEach(b => {
        b.onclick = () => { expensesPage = +b.dataset.p; loadGroupExpenses(); };
      });
    };

    const renderActivity = (actList) => {
      const container = document.getElementById('activity-list');
      if (!container) return;
      container.innerHTML = actList?.length ? actList.map(a => `
        <div class="activity-item" style="padding:12px 0; border-bottom:1px solid var(--border-light);">
          <div class="activity-message" style="font-weight:500; display:flex; justify-content:space-between; align-items:center; gap: 12px;">
            <span style="flex: 1; min-width: 0; word-break: break-word;">${esc(a.message)}</span>
            ${a.activityType === 'SETTLEMENT_RECORDED' ? `<button class="btn btn-danger btn-sm revert-btn" data-id="${a.referenceId}" title="Revert Settlement" style="padding:4px 8px; font-size:0.75rem; flex-shrink: 0;">Revert</button>` : ''}
          </div>
          <div class="activity-meta" style="font-size:0.8rem; color:var(--text-dim); margin-top:6px;">${formatDate(a.createdAt)} · ${esc(activityTypeLabel(a.activityType))}</div>
        </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem;text-align:center;padding:20px;">No activity yet</p>';
        
      container.querySelectorAll('.revert-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          const expenseId = e.currentTarget.dataset.id;
          if (await confirmModal('Revert Settlement', 'Are you sure you want to revert this settlement? The balances will be restored.', 'Revert')) {
            try {
              await api.delete(`/upsert/groups/${groupId}/expenses/${expenseId}`, { userId });
              toast('Settlement reverted successfully', 'success');
              loadGroupDetail(groupId, userId);
            } catch (err) { toast(err.message, 'error'); }
          }
        });
      });
      renderActivityPagination();
    };

    const loadGroupActivity = async () => {
      try {
        const res = await api.get(`/upsert/groups/${groupId}/activity`, { page: activityPage, size: 8 });
        activityTotal = res.totalPages || 1;
        renderActivity(res.content || []);
      } catch (err) { toast(err.message, 'error'); }
    };

    const renderActivityPagination = () => {
      const el = document.getElementById('activity-pagination');
      if (!el || activityTotal <= 1) { if (el) el.innerHTML = ''; return; }
      
      let html = `<button class="btn btn-secondary btn-icon" id="ap-prev" ${activityPage === 0 ? 'disabled' : ''} aria-label="Previous Page">${icon('chevron-left', 'sm')}</button>`;
      
      let startPage = Math.max(0, activityPage - 2);
      let endPage = Math.min(activityTotal - 1, startPage + 4);
      if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
      
      for (let i = startPage; i <= endPage; i++) {
        html += `<button class="${i === activityPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`;
      }
      
      html += `<button class="btn btn-secondary btn-icon" id="ap-next" ${activityPage === activityTotal - 1 ? 'disabled' : ''} aria-label="Next Page">${icon('chevron-right', 'sm')}</button>`;
      
      el.innerHTML = html;
      
      if (activityPage > 0) {
        document.getElementById('ap-prev').onclick = () => { activityPage--; loadGroupActivity(); };
      }
      if (activityPage < activityTotal - 1) {
        document.getElementById('ap-next').onclick = () => { activityPage++; loadGroupActivity(); };
      }
      
      el.querySelectorAll('button[data-p]').forEach(b => {
        b.onclick = () => { activityPage = +b.dataset.p; loadGroupActivity(); };
      });
    };

    // Initial render for expenses
    renderExpenses(expenses);

    // Load activity asynchronously
    activityPromise.then(activityResult => {
      activityTotal = activityResult.totalPages || 1;
      renderActivity(activityResult.content || []);
    }).catch(e => {
      const container = document.getElementById('activity-list');
      if (container) container.innerHTML = `<p style="color:var(--accent);text-align:center;padding:20px;">Failed to load activity.</p>`;
    });

    // Wire group action buttons from dropdown
    const hideMenu = () => { const m = document.getElementById('sp-group-menu'); if (m) m.style.display = 'none'; };

    document.getElementById('sp-edit-group')?.addEventListener('click', () => {
      hideMenu(); editGroupModal(group, userId);
    });

    document.getElementById('sp-archive-group')?.addEventListener('click', async () => {
      hideMenu();
      if (!(await confirmModal(`${group.isArchived ? 'Unarchive' : 'Archive'} Group`, `Are you sure you want to ${group.isArchived ? 'unarchive' : 'archive'} this group?`, group.isArchived ? 'Unarchive' : 'Archive'))) return;
      try {
        await api.put(`/upsert/groups/${groupId}/archive`, null, { params: { archive: !group.isArchived } });
        toast(`Group ${group.isArchived ? 'unarchived' : 'archived'}`, 'success');
        document.getElementById('sp-back').click();
      } catch (err) { toast(err.message, 'error'); }
    });

    document.getElementById('sp-leave-group')?.addEventListener('click', async () => {
      hideMenu();
      if (!(await confirmModal('Leave Group', 'Are you sure you want to leave this group?', 'Leave'))) return;
      try {
        await api.delete(`/upsert/groups/${groupId}/members/${userId}`);
        toast('Left group', 'info');
        document.getElementById('sp-back').click();
      } catch (err) { toast(err.message, 'error'); }
    });

    document.getElementById('sp-delete-group')?.addEventListener('click', async () => {
      hideMenu();
      if (!(await confirmModal('Delete Group', 'Are you sure you want to PERMANENTLY delete this group? All expenses must be settled.', 'Delete'))) return;
      try {
        await api.delete(`/upsert/groups/${groupId}`, { userId });
        toast('Group deleted', 'success');
        document.getElementById('sp-back').click();
      } catch (err) { toast(err.message, 'error'); }
    });

  } catch (err) {
    el.innerHTML = `<p style="color:var(--accent)">${esc(err.message)}</p>`;
  }
}

function openMobileExpenseModal(e, groupId, userId) {
  openModal('Expense Details', `
    <div style="text-align:center; padding: 20px 0;">
      <div style="font-size: 2rem; font-weight: 800; color: var(--text); margin-bottom: 8px;">
        ${formatCurrency(e.amount, e.currency || 'INR')}
      </div>
      <div style="font-size: 1.1rem; font-weight: 600;">${esc(e.description)}</div>
      <div style="color: var(--text-dim); font-size: 0.9rem; margin-top: 4px;">Paid by @${esc(e.paidByName || e.paidBy)}</div>
    </div>
    
    <div style="background: var(--bg-input); border-radius: var(--radius-sm); padding: 16px; margin-bottom: 20px;">
      <div style="display:flex; justify-content:space-between; margin-bottom: 12px;">
        <span style="color:var(--text-dim)">Split Type</span>
        <span style="font-weight:600">${esc(splitTypeLabel(e.splitType))}</span>
      </div>
      <div style="display:flex; justify-content:space-between; ${e.receiptUrl ? 'margin-bottom: 12px;' : ''}">
        <span style="color:var(--text-dim)">Category</span>
        <span>${esc(e.expenseCategory || 'OTHERS')}</span>
      </div>
      ${e.receiptUrl ? `
      <div style="display:flex; flex-direction:column; gap:6px;">
        <span style="color:var(--text-dim)">Receipt</span>
        <div style="cursor:pointer" onclick="event.stopPropagation(); window.openReceiptLightbox('${esc(e.receiptUrl)}')">
          <img src="${esc(e.receiptUrl)}" style="max-width:100%; max-height:200px; border-radius:8px; border:1px solid var(--border)">
        </div>
      </div>` : ''}
    </div>
    
    <div style="display:flex; gap: 12px;">
      <button class="btn btn-danger" id="m-del-exp-btn" style="flex:1; width:100%;">${icon('trash', 'sm')} Delete Expense</button>
    </div>
  `);

  document.getElementById('m-del-exp-btn').onclick = async () => {
    if (!(await confirmModal('Delete Expense', 'Are you sure you want to delete this shared expense?', 'Delete'))) return;
    try {
      await api.delete(`/upsert/groups/${groupId}/expenses/${e.id}`, { userId });
      toast('Expense deleted', 'success');
      document.querySelector('.modal-overlay').click(); // Close current modal
      loadGroupDetail(groupId, userId);
    } catch (err) { toast(err.message, 'error'); }
  };
}

function viewMembersModal(members, groupId, userId) {
  openModal('Group Members', `
    <div style="margin-bottom: 20px; max-height: 400px; overflow-y: auto;">
      ${members.map((m, i) => `<div class="member-row" data-index="${i}" style="padding:12px; border-bottom:1px solid var(--border-light); display:flex; justify-content:space-between; align-items:center; cursor:pointer; transition: background 0.2s;" onmouseover="this.style.background='var(--bg-elevated)'" onmouseout="this.style.background='transparent'">
        <div style="display:flex; align-items:center; gap:8px;">${avatar(m.name, 28, m.profilePicture)}<span style="font-size:1.05rem; font-weight:500;">@${esc(m.name)}</span></div>
        ${m.status === 'PENDING' ? '<span class="badge badge-warning" style="font-size:.68rem">Pending</span>' : '<span class="badge badge-success" style="font-size:.68rem; background:var(--accent-g); color:#000;">Accepted</span>'}
      </div>`).join('')}
    </div>
    <button class="btn btn-secondary" style="width:100%" id="vm-add-member">+ Add Member</button>
  `, {
    onSubmit: () => {}
  });
  
  // Wait for modal to render
  setTimeout(() => {
    document.getElementById('vm-add-member').onclick = () => {
      document.querySelector('.modal-close').click();
      addMemberModal(groupId, userId);
    };
    
    document.querySelectorAll('.member-row').forEach(row => {
      row.onclick = () => {
        const index = parseInt(row.getAttribute('data-index'));
        const member = members[index];
        document.querySelector('.modal-close').click();
        viewMemberProfileModal(member, members, groupId, userId);
      };
    });
  }, 50);
}

function viewMemberProfileModal(member, members, groupId, userId) {
  const isMe = member.userId === userId;
  openModal('Member Profile', `
    <div style="display:flex;flex-direction:column;align-items:center;gap:20px;padding:10px 10px 30px 10px;text-align:center;">
      <div style="position:relative;">
        ${member.profilePicture 
          ? `<img src="${member.profilePicture}" style="width:160px;height:160px;border-radius:50%;object-fit:cover;box-shadow:0 8px 24px rgba(0,0,0,0.2);" />` 
          : `<div style="width:160px;height:160px;border-radius:50%;background:var(--bg-elevated);display:flex;align-items:center;justify-content:center;font-size:3.5rem;color:var(--text-dim);box-shadow:0 8px 24px rgba(0,0,0,0.2);">${(member.name || 'U')[0].toUpperCase()}</div>`
        }
      </div>
      <div>
        <h2 style="margin-bottom:4px; font-size:1.6rem;">@${esc(member.name)}</h2>
        <div style="color:var(--text-dim); font-size:1.05rem;">${esc(member.email || 'No email available')}</div>
      </div>
      
      <div style="width:100%; max-width:300px; margin-top:16px;">
        <div style="display:flex; justify-content:space-between; padding:12px; border-bottom:1px solid var(--border-light);">
          <span style="color:var(--text-dim);">Status</span>
          <span style="font-weight:600;">${member.status}</span>
        </div>
      </div>
    </div>
    
    <div style="display:flex; gap:12px;">
      <button class="btn btn-secondary" style="flex:1" id="vmp-back">Back to Members</button>
      ${isMe ? '' : '<button class="btn btn-primary" style="flex:1" disabled title="Coming soon">Send Money</button>'}
    </div>
  `, {
    onSubmit: () => {}
  });

  setTimeout(() => {
    document.getElementById('vmp-back').onclick = () => {
      document.querySelector('.modal-close').click();
      viewMembersModal(members, groupId, userId);
    };
  }, 50);
}

function editGroupModal(group, userId) {
  openModal('Edit Group', `
    <form id="eg-form">
      <div class="form-group"><label for="eg-name">Group Name</label><input class="form-input" id="eg-name" required maxlength="100" value="${esc(group.name)}"></div>
      <div class="form-group"><label for="eg-desc">Description</label><textarea class="form-textarea" id="eg-desc" maxlength="300">${esc(group.description || '')}</textarea></div>
      <div class="form-group"><label for="eg-currency">Currency</label><input class="form-input" id="eg-currency" value="${esc(group.currency || 'INR')}" maxlength="3" required></div>

      ${modalActions('Cancel', 'Save Changes')}
    </form>`, {
    onSubmit: async () => {
      await api.put(`/upsert/groups/${group.id}`, {
        name: document.getElementById('eg-name').value.trim(),
        description: document.getElementById('eg-desc').value.trim() || undefined,
        currency: document.getElementById('eg-currency').value.trim().toUpperCase()
      });
      toast('Group updated', 'success');
      loadGroupDetail(group.id, userId);
    }
  });

  setTimeout(() => {
      document.getElementById('eg-archive-btn').onclick = async () => {
          if (!(await confirmModal('Archive Group', 'Are you sure you want to archive this group? It will be hidden from the active list.', 'Archive'))) return;
          try {
              await api.put(`/upsert/groups/${group.id}/archive`, null, { params: { archive: true } });
              toast('Group archived status updated', 'success');
              document.querySelector('.modal-close').click();
              loadGroups(userId); // reload groups list to show/hide it
          } catch (err) { toast(err.message, 'error'); }
      };

      document.getElementById('eg-delete-btn').onclick = async () => {
          if (!(await confirmModal('Delete Group', 'Are you sure you want to PERMANENTLY delete this group? All expenses must be settled.', 'Delete'))) return;
          try {
              await api.delete(`/upsert/groups/${group.id}`, { userId });
              toast('Group deleted', 'success');
              document.querySelector('.modal-close').click();
              document.getElementById('sp-back').click(); // Go back to list
          } catch (err) { toast(err.message, 'error'); }
      };
  }, 50);
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

  let currentPage = 0;

  const performSearch = async (append = false) => {
    const q = searchInput.value.trim();
    if (q.length < 2) {
      resultsEl.hidden = true;
      resultsEl.innerHTML = '';
      return;
    }
    
    try {
      const limit = 8;
      const users = await api.get('/auth/users/search', { q, limit, page: currentPage });
      
      const renderUser = u => `
        <button type="button" class="user-search-item" data-id="${esc(u.userId)}" data-name="${esc(u.username)}">
          <strong>@${esc(u.username)}</strong>
          <span>${esc(u.email)}</span>
        </button>`;

      if (!users.length && !append) {
        resultsEl.innerHTML = '<div class="user-search-empty">No users found</div>';
      } else {
        const loadMoreBtn = document.getElementById('am-load-more');
        if (loadMoreBtn) loadMoreBtn.remove(); // Remove existing load more button before appending
        
        const html = users.map(renderUser).join('');
        if (append) {
          resultsEl.insertAdjacentHTML('beforeend', html);
        } else {
          resultsEl.innerHTML = html;
        }

        // Re-attach click listeners
        resultsEl.querySelectorAll('.user-search-item').forEach(btn => {
          btn.onclick = () => {
            document.getElementById('am-uid').value = btn.dataset.id;
            document.getElementById('am-name').value = btn.dataset.name;
            searchInput.value = `@${btn.dataset.name}`;
            selectedEl.textContent = `Selected: @${btn.dataset.name}`;
            resultsEl.hidden = true;
          };
        });

        // Add "Load More" button if we got a full page
        if (users.length === limit) {
          resultsEl.insertAdjacentHTML('beforeend', `
            <button type="button" id="am-load-more" class="user-search-item" style="justify-content: center; color: var(--primary);">
              Load More
            </button>
          `);
          document.getElementById('am-load-more').onclick = () => {
            currentPage++;
            document.getElementById('am-load-more').textContent = 'Loading...';
            performSearch(true);
          };
        }
      }
      resultsEl.hidden = false;
    } catch (e) {
      if (!append) {
        resultsEl.innerHTML = `<div class="user-search-empty">${esc(e.message)}</div>`;
        resultsEl.hidden = false;
      } else {
        toast(e.message, 'error');
      }
    }
  };

  searchInput.oninput = () => {
    clearTimeout(debounceTimer);
    document.getElementById('am-uid').value = '';
    document.getElementById('am-name').value = '';
    selectedEl.textContent = '';
    currentPage = 0;
    debounceTimer = setTimeout(() => performSearch(false), 300);
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

function addExpenseModal(groupId, members, userId, initialData = null) {
  if (!members.length) {
    toast('Add at least one member before creating an expense', 'error');
    return;
  }
  
  const defaultDesc = initialData ? esc(initialData.description) : '';
  const defaultAmt = initialData && initialData.amount ? initialData.amount : '';
  const defaultCur = initialData && initialData.currency ? esc(initialData.currency) : 'INR';
  const defaultDate = initialData && initialData.date ? esc(initialData.date) : '';
  const defaultReceipt = initialData && initialData.receiptUrl ? esc(initialData.receiptUrl) : '';

  openModal('Add Shared Expense', `
    <form id="ae-form">
      <div class="form-group"><label for="ae-desc">Description</label><input class="form-input" id="ae-desc" required maxlength="100" value="${defaultDesc}"></div>
      <div class="form-row">
        <div class="form-group"><label for="ae-amt">Amount</label><input class="form-input" id="ae-amt" type="text" inputmode="decimal" required value="${defaultAmt}"></div>
        <div class="form-group"><label for="ae-paid">Paid By</label>
          <select class="form-select" id="ae-paid" required>
            ${members.map(m => `<option value="${m.userId}">@${esc(m.name)}</option>`).join('')}
          </select>
        </div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="ae-currency">Currency</label><input class="form-input" id="ae-currency" value="${defaultCur}" maxlength="3" required></div>
        <div class="form-group">
          <label for="ae-split-type">Split Type</label>
          <select class="form-select" id="ae-split-type">
            <option value="EQUAL">Split equally</option>
            <option value="PERCENTAGE">Split by percentage</option>
            <option value="EXACT">Split by exact amount</option>
          </select>
        </div>
      </div>
      <div class="form-group" style="margin-bottom: 12px;">
        <label for="ae-date">Expense Date</label>
        <input class="form-input" id="ae-date" type="date" value="${defaultDate}">
      </div>
      <div id="ae-split-details" hidden></div>
      <div class="form-group" style="margin-top: 12px">
        <label>Receipt / Bill Attachment</label>
        <div id="ae-receipt-preview" style="display:${defaultReceipt ? 'block' : 'none'};margin-bottom:8px;position:relative;width:fit-content">
          ${defaultReceipt ? `<img src="${defaultReceipt}" style="max-height:100px;border-radius:6px;border:1px solid var(--border)">
          <button type="button" class="btn btn-icon btn-sm btn-danger" id="ae-receipt-remove" style="position:absolute;top:-8px;right:-8px" title="Remove Receipt">×</button>` : ''}
        </div>
        <div id="ae-receipt-upload" style="display:${defaultReceipt ? 'none' : 'block'}">
          <input type="file" id="ae-receipt-file" accept="image/*,application/pdf" class="form-input" style="padding:6px">
          <p style="font-size:0.75rem;color:var(--text-dim);margin-top:4px">Upload a receipt image or PDF (max 10MB)</p>
        </div>
        <input type="hidden" id="ae-receipt-url" value="${defaultReceipt}">
      </div>
      ${modalActions('Cancel', 'Add')}
    </form>`, {
    onSubmit: async () => {
      const splitType = document.getElementById('ae-split-type').value;
      const amount = parseFloat(document.getElementById('ae-amt').value);
      const expenseDate = document.getElementById('ae-date').value;
      
      const submitBtn = document.querySelector('#ae-form button[type="submit"]');
      const fileInput = document.getElementById('ae-receipt-file');
      if (fileInput && fileInput.files.length > 0) {
        submitBtn.disabled = true;
        const originalText = submitBtn.textContent;
        submitBtn.innerHTML = '<span class="spinner"></span> Uploading...';
        try {
          const fd = new FormData();
          fd.append('file', fileInput.files[0]);
          const upRes = await api.upload(`/receipt/upload/${userId}`, fd);
          if (upRes && upRes.url) {
             document.getElementById('ae-receipt-url').value = upRes.url;
          }
        } catch (e) {
          toast('Failed to upload receipt: ' + e.message, 'error');
          submitBtn.disabled = false;
          submitBtn.textContent = originalText;
          return;
        }
        submitBtn.textContent = originalText;
      }

      const payload = {
        description: document.getElementById('ae-desc').value.trim(),
        amount: amount,
        currency: document.getElementById('ae-currency').value.trim().toUpperCase(),
        paidBy: document.getElementById('ae-paid').value,
        splitType: splitType,
        receiptUrl: document.getElementById('ae-receipt-url').value || null
      };
      
      let timeStr = '00:00:00';
      let isoDate = null;
      if (expenseDate) {
        const today = new Date();
        const todayStr = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');
        if (expenseDate === todayStr) {
          timeStr = String(today.getHours()).padStart(2, '0') + ':' + 
                    String(today.getMinutes()).padStart(2, '0') + ':' + 
                    String(today.getSeconds()).padStart(2, '0');
        }
        isoDate = new Date(`${expenseDate}T${timeStr}`).toISOString();
      }

      if (isoDate) {
         payload.expenseDate = isoDate;
      }

      if (splitType === 'PERCENTAGE' || splitType === 'EXACT') {
        const splitDetails = members.map(m => ({
          userId: m.userId,
          userName: m.name,
          value: parseFloat(document.getElementById(`ae-split-${m.userId}`).value) || 0
        }));
        const total = splitDetails.reduce((sum, d) => sum + d.value, 0);
        if (splitType === 'PERCENTAGE' && Math.abs(total - 100) > 0.0001) {
          toast('Percentages must add up to 100%', 'error');
          throw new Error('Invalid percentage split');
        }
        if (splitType === 'EXACT' && Math.abs(total - amount) > 0.0001) {
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

  if (window.TomSelect) {
    new TomSelect('#ae-paid', { create: false, controlInput: null, sortField: { field: 'text', direction: 'asc' }});
    new TomSelect('#ae-split-type', { create: false, controlInput: null });
  }

  const rmReceiptBtn = document.getElementById('ae-receipt-remove');
  if (rmReceiptBtn) {
    rmReceiptBtn.onclick = () => {
      document.getElementById('ae-receipt-preview').style.display = 'none';
      document.getElementById('ae-receipt-upload').style.display = 'block';
      document.getElementById('ae-receipt-url').value = '';
    };
  }

  function defaultSplitValue(type, amount) {
    if (!members.length) return 0;
    if (type === 'PERCENTAGE') return Math.round((100 / members.length) * 10000) / 10000;
    return Math.round((amount / members.length) * 10000) / 10000;
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
      const ok = Math.abs(total - 100) <= 0.0001;
      summary.textContent = `Total: ${total.toFixed(4)}% ${ok ? '' : '(must equal 100%)'}`;
      summary.style.color = ok ? 'var(--accent-g)' : 'var(--accent)';
    } else {
      const ok = Math.abs(total - amount) <= 0.0001;
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
                type="text" inputmode="decimal"
                ${isPct ? 'max="100"' : ''} value="${defaultVal}" required>
              ${isPct ? '<span class="split-detail-suffix">%</span>' : ''}
            </div>
          </div>`).join('')}
        <p id="ae-split-summary" class="split-summary"></p>
      </div>`;

    const dirtyFlags = {};
    
    function runAdjustment() {
        const target = isPct ? 100 : (parseFloat(amountInput.value) || 0);
        let dirtySum = 0;
        let cleanCount = 0;
        
        members.forEach(member => {
            if (dirtyFlags[member.userId]) {
                dirtySum += (parseFloat(document.getElementById(`ae-split-${member.userId}`).value) || 0);
            } else {
                cleanCount++;
            }
        });
        
        if (cleanCount > 0) {
            const remainder = Math.max(0, target - dirtySum);
            const perClean = Math.floor((remainder / cleanCount) * 10000) / 10000;
            
            members.forEach(member => {
                if (!dirtyFlags[member.userId]) {
                    document.getElementById(`ae-split-${member.userId}`).value = perClean;
                }
            });
            
            const cleanMembers = members.filter(member => !dirtyFlags[member.userId]);
            if (cleanMembers.length > 0) {
                 const lastClean = cleanMembers[cleanMembers.length - 1];
                 const othersSum = dirtySum + perClean * (cleanCount - 1);
                 document.getElementById(`ae-split-${lastClean.userId}`).value = Math.max(0, Math.round((target - othersSum) * 10000) / 10000);
            }
        }
        updateSplitSummary();
    }

    members.forEach(m => {
      const inputEl = document.getElementById(`ae-split-${m.userId}`);
      inputEl.addEventListener('input', (e) => {
        dirtyFlags[m.userId] = true;
        runAdjustment();
      });
    });
    
    // Initial exact distribution
    runAdjustment();

    amountInput.oninput = () => {
      if (splitTypeSelect.value === 'EXACT') {
          runAdjustment();
      }
    };
  }

  splitTypeSelect.onchange = renderSplitDetails;
}
