import { api, Auth, toast } from '../utils/api.js';
import { esc, pageHeader, emptyState, formatCurrency, formatDate, openModal, confirmModal, modalActions } from '../utils/ui.js';
import { icon } from '../utils/icons.js';
import { loadTomSelect } from '../utils/loader.js';

let currentGroupId = null;

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
    if (err.name === 'AbortError') return;
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
    const [group, members] = await Promise.all([
      api.get(`/upsert/groups/${groupId}`),
      api.get(`/upsert/groups/${groupId}/members`)
    ]);

    let pendingScan = null;
    if (window._pfaPendingScan && String(window._pfaPendingScan.groupId) === String(groupId)) {
       pendingScan = window._pfaPendingScan;
       delete window._pfaPendingScan;
       const acceptedMembers = members.filter(m => m.status === 'ACCEPTED');
       // Open modal immediately while background loads
       try { addExpenseModal(groupId, acceptedMembers, userId, pendingScan); } catch (err) {}
    }

    const [expenses, balances, activity] = await Promise.all([
      api.get(`/upsert/groups/${groupId}/expenses`),
      api.get(`/upsert/groups/${groupId}/balances`),
      api.get(`/upsert/groups/${groupId}/activity`)
    ]);
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

    const acceptedMembers = members.filter(m => m.status === 'ACCEPTED' || !m.status);
    const myBalanceObj = balances?.memberBalances?.find(b => b.userId === userId);
    const myNetBalance = myBalanceObj ? myBalanceObj.netBalance : 0;

    el.innerHTML = `
      <div class="card" id="group-header-card" style="margin-bottom:20px;">
        <div id="group-header-collapsed" style="cursor:pointer; display:flex; justify-content:space-between; align-items:flex-start;">
          <div style="overflow:hidden; padding-right: 16px;">
            <h3 style="margin:0;">${esc(group.name)}</h3>
            <p id="gh-short-desc" style="color:var(--text-dim); margin-top:4px; margin-bottom:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${esc(group.description || '')}</p>
          </div>
          <div id="gh-expand-icon" style="color:var(--text-muted); padding-top:4px; transition: transform 0.3s;">${icon('chevron-down')}</div>
        </div>
        
        <div id="group-header-expanded" style="display:none; border-top:1px solid var(--border); padding-top:16px; margin-top:16px;">
          <p style="color:var(--text);margin-bottom:16px;line-height:1.5;">${esc(group.description || 'No description provided')}</p>
          <div style="display:flex; gap:8px; flex-wrap:wrap;">
             <button class="btn btn-secondary btn-sm" id="sp-edit-group">Edit Group</button>
             <button class="btn btn-secondary btn-sm" id="sp-archive-group" style="background:var(--bg-card-h); border:1px solid var(--border);">${group.isArchived ? 'Unarchive' : 'Archive'}</button>
             <button class="btn btn-danger btn-sm" id="sp-delete-group" style="background:rgba(239, 68, 68, 0.1); color:var(--accent);">Delete Group</button>
             <button class="btn btn-danger btn-sm" id="sp-leave-group">Leave Group</button>
          </div>
        </div>
        
        <div style="display:flex; align-items:center; margin-top:16px; gap:8px;">
          <div id="sp-view-members" style="background:var(--bg-card); padding:6px 12px; border-radius:var(--radius-sm); border:1px solid var(--border); font-size:0.9rem; cursor:pointer; color:var(--text); font-weight:500; transition:var(--transition); display:flex; align-items:center; gap:6px;">
            ${icon('users', 'sm')} ${members.length} Members
          </div>
          ${myNetBalance !== 0 ? `
          <div style="background:rgba(${myNetBalance > 0 ? '34, 201, 147' : '249, 115, 22'}, 0.1); padding:6px 12px; border-radius:var(--radius-sm); border:1px solid ${myNetBalance > 0 ? 'var(--accent-g)' : 'var(--accent)'}; font-size:0.9rem; color:${myNetBalance > 0 ? 'var(--accent-g)' : 'var(--accent)'}; font-weight:700;">
            ${myNetBalance > 0 ? 'Gets back ' + formatCurrency(myNetBalance) : 'You owe ' + formatCurrency(Math.abs(myNetBalance))}
          </div>` : ''}
        </div>
      </div>
    
    <div class="card" style="padding:0; overflow:hidden;">
      <div class="tab-header" style="display:flex; border-bottom: 1px solid var(--border); background: var(--bg-card-h); overflow-x: auto;">
        <button class="tab-btn active" id="tab-expenses" style="flex:1; padding:16px; background:transparent; border:none; color:var(--text); font-weight:600; cursor:pointer; border-bottom: 2px solid var(--primary); transition: var(--transition);">Expenses</button>
        <button class="tab-btn" id="tab-balances" style="flex:1; padding:16px; background:transparent; border:none; color:var(--text-dim); font-weight:600; cursor:pointer; border-bottom: 2px solid transparent; transition: var(--transition);">Balances</button>
        <button class="tab-btn" id="tab-activity" style="flex:1; padding:16px; background:transparent; border:none; color:var(--text-dim); font-weight:600; cursor:pointer; border-bottom: 2px solid transparent; transition: var(--transition);">Activity</button>
      </div>
      
      <div id="content-expenses" style="padding: 20px;">
        <button class="btn btn-primary btn-sm" style="margin-bottom:16px;width:100%" id="sp-add-expense" ${members.length ? '' : 'disabled'}>+ Add Expense</button>
        ${expenses.length ? expenses.map(e => {
            const expData = { ...e, paidByName: resolveMemberNameLocally(e.paidBy) };
            return `
          <div class="balance-item expense-row" data-exp='${esc(JSON.stringify(expData))}' style="padding:16px 0; border-bottom:1px solid var(--border-light); display:flex; justify-content:space-between; align-items:center; cursor:pointer;">
            <div>
               <div style="font-weight:600; font-size:1.05rem;">${esc(e.description || 'Expense')}</div>
               <div style="margin-top:4px; font-size:0.85rem; color:var(--text-dim);" class="desktop-only">Paid by @${esc(resolveMemberNameLocally(e.paidBy))} · <span class="badge badge-info" style="font-size:.68rem;">${esc(splitTypeLabel(e.splitType))}</span></div>
            </div>
            <div class="expense-row-actions" style="display:flex; align-items:center; gap: 12px;">
              <span style="font-weight:700;color:var(--text);font-size:1.1rem;">${formatCurrency(e.amount)}</span>
              <button type="button" class="btn btn-danger btn-sm delete-expense-btn" data-id="${e.id}" title="Delete expense" style="padding:6px 10px;">×</button>
            </div>
          </div>`;
        }).join('') : '<p style="color:var(--text-dim);font-size:.88rem;text-align:center;padding:20px;">No expenses yet</p>'}
      </div>
      
      <div id="content-balances" style="padding: 20px; display:none;">
        <h4 style="margin-bottom:12px; color:var(--text-muted); text-transform:uppercase; font-size:0.8rem; letter-spacing:0.5px;">Net Balances</h4>
        <div style="margin-bottom:24px; display:grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap:12px;">
          ${balances?.memberBalances?.length ? balances.memberBalances.map(b => `
            <div style="background:var(--bg-card); padding:12px; border-radius:var(--radius-sm); border:1px solid var(--border); display:flex; flex-direction:column; gap:4px;">
              <span style="color:var(--text-dim); font-size:0.8rem; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">@${esc(b.userName || b.userId?.substring(0, 8))}</span>
              <span style="font-weight:700;color:${b.netBalance >= 0 ? 'var(--accent-g)' : 'var(--accent)'}; font-size:1.1rem;">
                ${b.netBalance >= 0 ? '+' : ''}${formatCurrency(b.netBalance)}
              </span>
            </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem;">No balance data</p>'}
        </div>
        
        <h4 style="margin-bottom:12px; color:var(--text-muted); text-transform:uppercase; font-size:0.8rem; letter-spacing:0.5px;">Settlements</h4>
        ${balances?.simplifiedDebts?.length ? balances.simplifiedDebts.map(s => {
          let text = '';
          let amountColor = 'var(--text)';
          let amountSign = '';
          if (s.fromUserId === userId) {
            text = `You owe <strong>${esc(s.toUserName)}</strong>`;
            amountColor = 'var(--accent)';
            amountSign = '-';
          } else if (s.toUserId === userId) {
            text = `<strong>${esc(s.fromUserName)}</strong> owes you`;
            amountColor = 'var(--accent-g)';
            amountSign = '+';
          } else {
            text = `<strong>${esc(s.fromUserName)}</strong> owes <strong>${esc(s.toUserName)}</strong>`;
          }
          return `
          <div class="settlement-row" style="display:flex; justify-content:space-between; align-items:center; padding:16px 0; border-bottom:1px solid var(--border); gap: 16px;">
            <div style="font-size: 1rem; flex: 1; min-width: 0; word-break: break-word;">
              ${text}
              <span style="font-weight:800;color:${amountColor};margin-left:8px;font-size:1.1rem;">${amountSign}${formatCurrency(s.amount)}</span>
            </div>
            <button class="btn btn-secondary btn-sm settle-btn" style="flex-shrink: 0;"
              data-from="${s.fromUserId}" data-to="${s.toUserId}"
              data-from-name="${esc(s.fromUserName)}" data-to-name="${esc(s.toUserName)}">
              Record Payment
            </button>
          </div>`;
        }).join('') : '<p style="color:var(--text-dim);margin:0;text-align:center;padding:20px;">All balances are settled.</p>'}
      </div>
      
      <div id="content-activity" style="padding: 20px; display:none;">
        ${activity?.length ? activity.slice(0, 20).map(a => `
          <div class="activity-item" style="padding:12px 0; border-bottom:1px solid var(--border-light);">
            <div class="activity-message" style="font-weight:500; display:flex; justify-content:space-between; align-items:center; gap: 12px;">
              <span style="flex: 1; min-width: 0; word-break: break-word;">${esc(a.message)}</span>
              ${a.activityType === 'SETTLEMENT_RECORDED' ? `<button class="btn btn-danger btn-sm revert-btn" data-id="${a.referenceId}" title="Revert Settlement" style="padding:4px 8px; font-size:0.75rem; flex-shrink: 0;">Revert</button>` : ''}
            </div>
            <div class="activity-meta" style="font-size:0.8rem; color:var(--text-dim); margin-top:6px;">${formatDate(a.createdAt)} · ${esc(activityTypeLabel(a.activityType))}</div>
          </div>`).join('') : '<p style="color:var(--text-dim);font-size:.88rem;text-align:center;padding:20px;">No activity yet</p>'}
      </div>
    </div>`;

    document.getElementById('sp-view-members').onclick = () => viewMembersModal(members, groupId, userId);
    document.getElementById('sp-view-members').onmouseover = (e) => e.target.style.background = 'var(--bg-card-h)';
    document.getElementById('sp-view-members').onmouseout = (e) => e.target.style.background = 'var(--bg-card)';
    
    // Group header expand/collapse
    document.getElementById('group-header-collapsed').onclick = () => {
      const exp = document.getElementById('group-header-expanded');
      const shortDesc = document.getElementById('gh-short-desc');
      const iconWrap = document.getElementById('gh-expand-icon');
      if (exp.style.display === 'none') {
        exp.style.display = 'block';
        shortDesc.style.display = 'none';
        iconWrap.style.transform = 'rotate(180deg)';
      } else {
        exp.style.display = 'none';
        shortDesc.style.display = 'block';
        iconWrap.style.transform = 'rotate(0deg)';
      }
    };
    
    // Tab switching logic
    const tabs = ['expenses', 'balances', 'activity'];
    tabs.forEach(t => {
      document.getElementById(`tab-${t}`).onclick = () => {
        tabs.forEach(other => {
          const isT = other === t;
          document.getElementById(`tab-${other}`).style.borderBottomColor = isT ? 'var(--primary)' : 'transparent';
          document.getElementById(`tab-${other}`).style.color = isT ? 'var(--text)' : 'var(--text-dim)';
          document.getElementById(`content-${other}`).style.display = isT ? 'block' : 'none';
        });
      };
    });
    
    document.getElementById('sp-add-expense').onclick = () => addExpenseModal(groupId, acceptedMembers, userId);
    
    // Mobile expense tap
    document.querySelectorAll('.expense-row').forEach(row => {
      row.onclick = () => {
        if (window.innerWidth > 768) return; // Only open modal on mobile
        const exp = JSON.parse(row.dataset.exp);
        openMobileExpenseModal(exp, groupId, userId);
      };
    });

    // Delete Expense
    document.querySelectorAll('.delete-expense-btn').forEach(btn => {
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

    // Revert Settlement
    document.querySelectorAll('.revert-btn').forEach(btn => {
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

    el.querySelectorAll('.settle-btn').forEach(btn => {
      btn.onclick = async () => {
        if (!(await confirmModal('Record Settlement', `Record settlement: did ${btn.dataset.fromName} pay ${btn.dataset.toName}?`, 'Record'))) return;
        try {
          await api.post(`/upsert/groups/${groupId}/settle`, null, { params: { fromUserId: btn.dataset.from, toUserId: btn.dataset.to } });
          toast('Settlement recorded!', 'success');
          await loadGroupDetail(groupId, userId);
        } catch (e) { toast(e.message, 'error'); }
      };
    });
    document.getElementById('sp-edit-group').onclick = () => editGroupModal(group, userId);

    document.getElementById('sp-archive-group').onclick = async () => {
      const action = group.isArchived ? 'Unarchive' : 'Archive';
      if (!(await confirmModal(`${action} Group`, `Are you sure you want to ${action.toLowerCase()} this group?`, action))) return;
      try {
        await api.put(`/upsert/groups/${group.id}/archive?archive=${!group.isArchived}`);
        toast(`Group ${action.toLowerCase()}d successfully`, 'success');
        if (!group.isArchived) { document.getElementById('sp-back').click(); } 
        else { loadGroupDetail(group.id, userId); }
      } catch(e) { toast(e.message, 'error'); }
    };

    document.getElementById('sp-delete-group').onclick = async () => {
      if (!(await confirmModal('Delete Group', 'Warning: This will permanently delete the group and all its expenses. This action cannot be undone. All balances MUST be settled first.', 'Delete Permanently'))) return;
      try {
        await api.delete(`/upsert/groups/${group.id}`, { userId });
        toast('Group deleted successfully', 'success');
        document.getElementById('sp-back').click();
      } catch(e) { toast(e.message, 'error'); }
    };
    document.getElementById('sp-leave-group').onclick = async () => {
      if (!(await confirmModal('Leave Group', 'Are you sure you want to leave this group? You must have a settled balance to leave.', 'Leave Group'))) return;
      try {
        await api.delete(`/upsert/groups/${groupId}/members/${userId}`);
        toast('You left the group.', 'success');
        document.getElementById('sp-back').click();
      } catch (e) {
        toast(e.message, 'error');
      }
    };


  } catch (err) {
    if (err.name === 'AbortError') return;
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
      <div style="display:flex; justify-content:space-between;">
        <span style="color:var(--text-dim)">Category</span>
        <span>${esc(e.expenseCategory || 'OTHERS')}</span>
      </div>
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
      ${members.map(m => `<div style="padding:12px; border-bottom:1px solid var(--border-light); display:flex; justify-content:space-between;">
        <span style="font-size:1.05rem; font-weight:500;">@${esc(m.name)}</span>
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
              await api.post(`/upsert/groups/${group.id}/archive`, null, { params: { userId } });
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
      ${modalActions('Cancel', 'Add')}
    </form>`, {
    onSubmit: async () => {
      const splitType = document.getElementById('ae-split-type').value;
      const amount = parseFloat(document.getElementById('ae-amt').value);
      const expenseDate = document.getElementById('ae-date').value;
      const payload = {
        description: document.getElementById('ae-desc').value.trim(),
        amount: amount,
        currency: document.getElementById('ae-currency').value.trim().toUpperCase(),
        paidBy: document.getElementById('ae-paid').value,
        splitType: splitType
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

  loadTomSelect().then(() => {
    if (window.TomSelect) {
      new TomSelect('#ae-paid', { create: false, controlInput: null, sortField: { field: 'text', direction: 'asc' }});
      new TomSelect('#ae-split-type', { create: false, controlInput: null });
    }
  }).catch(() => {});

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
