import { api, Auth, toast } from '../utils/api.js';
import {
  esc, pageHeader, emptyState, formatMoney, formatDate, formatCategory,
  openModal, confirmModal, modalActions, avatar, displayName, chip,
  EXPENSE_CATS, categoryOptions, currencyOptionHtml,
} from '../utils/ui.js';
import { icon } from '../utils/icons.js';

let currentGroupId = null;
let expensesPage = 0, expensesTotal = 1;
let activityPage = 0, activityTotal = 1;
let activePanel = 'expenses';

/* ── Global notifications ───────────────────────────────────────────────── */
window.addEventListener('group-invite', () => {
  const userId = Auth.getUserId();
  if (userId && !currentGroupId && document.getElementById('sp-content')) loadGroups(userId);
});

window.addEventListener('expense-added', (e) => {
  const userId = Auth.getUserId();
  const data = e.detail;
  if (userId && currentGroupId && data.groupId === currentGroupId && document.getElementById('sp-content')) {
    loadGroupDetail(currentGroupId, userId, { preserveView: true });
  }
});

window.addEventListener('debt-settled', (e) => {
  const userId = Auth.getUserId();
  const data = e.detail;
  if (userId && currentGroupId && data.groupId === currentGroupId && document.getElementById('sp-content')) {
    loadGroupDetail(currentGroupId, userId, { preserveView: true });
  }
});

export async function renderSplit(container) {
  currentGroupId = null;
  activePanel = 'expenses';
  const userId = Auth.getUserId();

  container.innerHTML = `
    ${pageHeader('Split expenses', '', `
      <button class="btn btn-secondary btn-sm" id="sp-back" hidden>Back to groups</button>
      <button class="btn btn-primary" id="sp-create">${icon('plus', 'sm')} New group</button>`)}
    <div id="sp-content"></div>`;

  document.getElementById('sp-create').onclick = () => createGroupModal(userId);
  document.getElementById('sp-back').onclick = () => {
    currentGroupId = null;
    loadGroups(userId);
  };

  const pendingScanRaw = localStorage.getItem('pfa_pending_scan');
  if (pendingScanRaw) {
    try {
      const pendingScan = JSON.parse(pendingScanRaw);
      localStorage.removeItem('pfa_pending_scan');
      window._pfaPendingScan = pendingScan;
      loadGroupDetail(pendingScan.groupId, userId);
      return;
    } catch { /* ignore a broken queued scan */ }
  }

  loadGroups(userId);
}

/* ── Group list ─────────────────────────────────────────────────────────── */
async function loadGroups(userId) {
  const el = document.getElementById('sp-content');
  if (!el) return;
  document.getElementById('sp-back').hidden = true;
  document.getElementById('sp-create').hidden = false;
  el.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';

  try {
    const groups = await api.get('/upsert/groups', { userId });
    if (!groups.length) {
      el.innerHTML = emptyState('users', 'No groups yet',
        'Create a group to split bills with the people you share costs with.',
        '<button class="btn btn-primary" id="sp-empty-create">New group</button>');
      document.getElementById('sp-empty-create').onclick = () => createGroupModal(userId);
      return;
    }

    const activeGroups = groups.filter(g => !g.isArchived);
    const archivedGroups = groups.filter(g => g.isArchived);

    // Member avatars live on a separate endpoint; load them for the visible cards.
    const memberMap = new Map();
    await Promise.all(activeGroups.map(async (g) => {
      try {
        const members = await api.get(`/upsert/groups/${g.id}/members`);
        memberMap.set(g.id, members.filter(m => m.status !== 'PENDING'));
      } catch { memberMap.set(g.id, []); }
    }));

    const owed = groups.reduce((sum, g) => sum + Math.max(0, Number(g.currentUserNetBalance) || 0), 0);
    const owes = groups.reduce((sum, g) => sum + Math.max(0, -(Number(g.currentUserNetBalance) || 0)), 0);
    const owedGroups = groups.filter(g => Number(g.currentUserNetBalance) > 0).length;
    const oweGroups = groups.filter(g => Number(g.currentUserNetBalance) < 0).length;
    const currency = Auth.getDefaultCurrency();

    let summary;
    if (owed > 0 && owes === 0) {
      summary = `
        <div class="split-summary">
          <div>
            <div class="split-summary-label">You are owed</div>
            <div class="hero-number" style="color:var(--pos)">${formatMoney(owed, currency)}</div>
          </div>
          <div class="split-summary-sub">across ${owedGroups} group${owedGroups === 1 ? '' : 's'}</div>
        </div>`;
    } else if (owes > 0 && owed === 0) {
      summary = `
        <div class="split-summary">
          <div>
            <div class="split-summary-label">You owe</div>
            <div class="hero-number" style="color:var(--neg)">${formatMoney(owes, currency)}</div>
          </div>
          <div class="split-summary-sub">across ${oweGroups} group${oweGroups === 1 ? '' : 's'}</div>
        </div>`;
    } else if (owed > 0 && owes > 0) {
      summary = `
        <div class="split-summary">
          <div>
            <div class="split-summary-label">Net</div>
            <div class="hero-number" style="color:${owed - owes >= 0 ? 'var(--pos)' : 'var(--neg)'}">${formatMoney(owed - owes, currency)}</div>
          </div>
          <div class="split-summary-sub">${formatMoney(owed, currency)} owed to you · ${formatMoney(owes, currency)} owed by you</div>
        </div>`;
    } else {
      summary = `
        <div class="split-summary">
          <div>
            <div class="split-summary-label">Balances</div>
            <div class="hero-number">All settled up</div>
          </div>
        </div>`;
    }

    let html = summary;

    if (activeGroups.length) {
      html += `<div class="card-grid card-grid-3">${activeGroups.map(g => groupCard(g, memberMap.get(g.id) || [])).join('')}</div>`;
    } else {
      html += `<p class="meta" style="padding:20px 0">No active groups.</p>`;
    }

    if (archivedGroups.length) {
      html += `
        <div style="margin-top:var(--s6);border-top:1px solid var(--line);padding-top:var(--s4)">
          <button type="button" class="btn btn-ghost btn-sm" id="sp-archived-toggle" aria-expanded="false">
            ${icon('archive', 'sm')} Archived groups (${archivedGroups.length})
          </button>
          <div id="archived-groups-container" hidden style="margin-top:var(--s4)">
            <div class="card-grid card-grid-3">
              ${archivedGroups.map(g => groupCard(g, memberMap.get(g.id) || [])).join('')}
            </div>
          </div>
        </div>`;
    }

    el.innerHTML = html;

    document.getElementById('sp-archived-toggle')?.addEventListener('click', () => {
      const container = document.getElementById('archived-groups-container');
      const open = container.hidden;
      container.hidden = !open;
      document.getElementById('sp-archived-toggle').setAttribute('aria-expanded', String(open));
    });

    wireGroupCardActions(el, userId);
  } catch (err) {
    el.innerHTML = `<p style="color:var(--neg)">${esc(err.message)}</p>`;
  }
}

function groupCard(g, members) {
  const isPending = g.currentUserStatus === 'PENDING';
  const isArchived = g.isArchived;
  const balance = Number(g.currentUserNetBalance) || 0;
  const currency = g.currency || Auth.getDefaultCurrency();

  let balanceHtml = '<div class="group-balance is-zero">Settled up</div>';
  if (balance > 0) balanceHtml = `<div class="group-balance is-pos">You get back ${formatMoney(balance, currency)}</div>`;
  else if (balance < 0) balanceHtml = `<div class="group-balance is-neg">You owe ${formatMoney(Math.abs(balance), currency)}</div>`;

  const shown = members.slice(0, 4);
  const extra = Math.max(0, members.length - shown.length);
  const memberHtml = shown.length ? `
    <div class="group-members">
      ${shown.map(m => avatar(m.name || m.userName, 26, m.profilePicture)).join('')}
      ${extra ? `<span class="group-members-count">+${extra}</span>` : `<span class="group-members-count">${members.length} member${members.length === 1 ? '' : 's'}</span>`}
    </div>` : '';

  return `
    <article class="group-card" data-id="${g.id}" role="button" tabindex="0"
      ${isPending ? 'aria-label="Invitation to ' + esc(g.name) + '"' : ''}
      ${isArchived ? 'style="opacity:.75"' : ''}>
      <div class="group-name">${esc(g.name)}</div>
      <div class="group-meta">${esc(g.description || '')}</div>
      ${memberHtml}
      ${balanceHtml}
      ${isPending ? `
        <div class="sp-pending-actions">
          <button class="btn btn-primary btn-sm sp-outside-accept" data-id="${g.id}">Accept</button>
          <button class="btn btn-secondary btn-sm sp-outside-decline" data-id="${g.id}">Decline</button>
        </div>` : ''}
      ${isArchived ? `
        <div class="sp-archived-actions">
          <button class="btn btn-secondary btn-sm sp-outside-unarchive" data-id="${g.id}">${icon('arrow-up', 'sm')} Unarchive</button>
        </div>` : ''}
    </article>`;
}

function wireGroupCardActions(el, userId) {
  el.querySelectorAll('.sp-outside-accept').forEach(btn => {
    btn.onclick = async (e) => {
      e.stopPropagation();
      try {
        await api.post(`/upsert/groups/${btn.dataset.id}/members/${userId}/accept`);
        toast('Invitation accepted', 'success');
        loadGroups(userId);
      } catch (err) { toast(err.message, 'error'); }
    };
  });

  el.querySelectorAll('.sp-outside-decline').forEach(btn => {
    btn.onclick = async (e) => {
      e.stopPropagation();
      if (!(await confirmModal('Decline invitation', 'You will no longer be part of this group.', 'Decline'))) return;
      try {
        await api.post(`/upsert/groups/${btn.dataset.id}/members/${userId}/reject`);
        toast('Invitation declined', 'info');
        loadGroups(userId);
      } catch (err) { toast(err.message, 'error'); }
    };
  });

  el.querySelectorAll('.sp-outside-unarchive').forEach(btn => {
    btn.onclick = async (e) => {
      e.stopPropagation();
      try {
        await api.put(`/upsert/groups/${btn.dataset.id}/archive`, null, { params: { archive: false } });
        toast('Group unarchived', 'success');
        loadGroups(userId);
      } catch (err) { toast(err.message, 'error'); }
    };
  });

  el.querySelectorAll('.group-card').forEach(card => {
    const open = () => loadGroupDetail(+card.dataset.id, userId);
    card.onclick = (e) => {
      if (e.target.closest('.sp-pending-actions') || e.target.closest('.sp-archived-actions')) return;
      open();
    };
    card.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); }
    };
  });
}

/* ── Group detail ───────────────────────────────────────────────────────── */
async function loadGroupDetail(groupId, userId, opts = {}) {
  if (!opts.preserveView) {
    expensesPage = 0;
    activityPage = 0;
    activePanel = 'expenses';
  }
  currentGroupId = groupId;
  const el = document.getElementById('sp-content');
  if (!el) return;
  document.getElementById('sp-back').hidden = false;
  document.getElementById('sp-create').hidden = true;
  el.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';

  try {
    const activityPromise = api.get(`/upsert/groups/${groupId}/activity`, { page: activityPage, size: 8 });
    const [group, membersRaw, expensesResult, balances] = await Promise.all([
      api.get(`/upsert/groups/${groupId}`),
      api.get(`/upsert/groups/${groupId}/members`),
      api.get(`/upsert/groups/${groupId}/expenses`, { page: expensesPage, size: 8 }),
      api.get(`/upsert/groups/${groupId}/balances`),
    ]);

    expensesTotal = expensesResult.totalPages || 1;
    const expenses = expensesResult.content || [];

    const members = membersRaw.map(m => ({
      ...m,
      displayName: displayName(m.name || m.userName, displayNameFromId(m.userId)),
    }));

    let pendingScan = null;
    if (window._pfaPendingScan && String(window._pfaPendingScan.groupId) === String(groupId)) {
      pendingScan = window._pfaPendingScan;
      delete window._pfaPendingScan;
      const eligible = members.filter(m => m.status === 'ACCEPTED');
      try { addExpenseModal(groupId, eligible, userId, pendingScan); } catch { /* modal can retry */ }
    }

    const me = members.find(m => m.userId === userId);
    if (me && me.status === 'PENDING') {
      renderInvitation(el, group, groupId, userId);
      return;
    }

    const eligibleMembers = members.filter(m => m.status === 'ACCEPTED');
    const nameOf = (id) => {
      const m = members.find(x => x.userId === id);
      return m ? m.displayName : displayNameFromId(id);
    };
    const avatarOf = (id) => {
      const m = members.find(x => x.userId === id);
      return m ? m.profilePicture : null;
    };

    const currency = group.currency || Auth.getDefaultCurrency();
    const debts = balances?.simplifiedDebts || [];
    const myBalanceObj = balances?.memberBalances?.find(b => b.userId === userId);
    const myNet = Number(myBalanceObj?.netBalance) || 0;

    const myDebts = debts.filter(d => d.fromUserId === userId || d.toUserId === userId);
    const iOwe = myDebts.filter(d => d.fromUserId === userId).reduce((s, d) => s + Number(d.amount || 0), 0);
    const owedToMe = myDebts.filter(d => d.toUserId === userId).reduce((s, d) => s + Number(d.amount || 0), 0);

    // Compact per-person balance list: only direct debts, left aligned.
    const balanceRows = members
      .filter(m => m.userId !== userId && m.status === 'ACCEPTED')
      .map(m => {
        const owesMe = myDebts.find(d => d.fromUserId === m.userId && d.toUserId === userId);
        const iOweThem = myDebts.find(d => d.fromUserId === userId && d.toUserId === m.userId);
        let amountHtml = '<span class="balance-person-amount is-zero">Settled up</span>';
        let settleBtn = '';
        if (owesMe) {
          const amt = Number(owesMe.amount) || 0;
          amountHtml = `<span class="balance-person-amount is-pos">owes you ${formatMoney(amt, currency)}</span>`;
          settleBtn = `<button type="button" class="btn btn-ghost btn-sm settle-btn" data-from="${m.userId}" data-to="${userId}" data-from-name="${esc(m.displayName)}" data-to-name="${esc(nameOf(userId))}">Settle up</button>`;
        } else if (iOweThem) {
          const amt = Number(iOweThem.amount) || 0;
          amountHtml = `<span class="balance-person-amount is-neg">you owe ${formatMoney(amt, currency)}</span>`;
          settleBtn = `<button type="button" class="btn btn-ghost btn-sm settle-btn" data-from="${userId}" data-to="${m.userId}" data-from-name="${esc(nameOf(userId))}" data-to-name="${esc(m.displayName)}">Settle up</button>`;
        }
        return `
          <div class="balance-person-row">
            ${avatar(m.displayName, 30, m.profilePicture)}
            <span class="balance-person-name">${esc(m.displayName)}</span>
            ${amountHtml}
            ${settleBtn}
          </div>`;
      }).join('');

    const balancesBlock = balanceRows ? `
      <section class="balances-block" aria-label="Balances in this group">
        <div class="balances-block-head">
          <span class="balances-block-title">${iOwe > 0 || owedToMe > 0
            ? `${owedToMe > 0 ? `You are owed ${formatMoney(owedToMe, currency)}` : ''}${owedToMe > 0 && iOwe > 0 ? ' · ' : ''}${iOwe > 0 ? `you owe ${formatMoney(iOwe, currency)}` : ''}`
            : 'All settled up'}</span>
          ${myDebts.length ? `<button type="button" class="btn btn-secondary btn-sm" id="sp-settle-all">Settle all</button>` : ''}
        </div>
        ${balanceRows}
      </section>` : '';

    el.innerHTML = `
      <div class="group-compact-header">
        <div>
          <div class="group-compact-title">${esc(group.name)}</div>
          <div class="group-compact-meta">
            <button type="button" class="group-compact-members" id="sp-view-members">${icon('users', 'sm')} ${members.length} member${members.length === 1 ? '' : 's'}</button>
            ${myNet !== 0 ? `<span class="group-balance-pill">${myNet > 0 ? `You get back ${formatMoney(myNet, currency)}` : `You owe ${formatMoney(Math.abs(myNet), currency)}`}</span>` : ''}
          </div>
        </div>
        <div class="group-compact-actions">
          <button class="btn-add-expense" id="sp-add-expense" ${eligibleMembers.length ? '' : 'disabled'}>${icon('plus', 'sm')} Add expense</button>
          <button class="btn-group-menu" id="sp-group-menu-btn" aria-label="Group options" aria-haspopup="menu">${icon('settings', 'sm')}</button>
        </div>
      </div>

      <div id="sp-group-menu" class="overflow-menu" role="menu" hidden style="position:relative;right:auto;margin-bottom:var(--s3);max-width:220px;margin-left:auto">
        <button type="button" role="menuitem" id="sp-edit-group">${icon('edit', 'sm')} Edit group</button>
        <button type="button" role="menuitem" id="sp-archive-group">${group.isArchived ? icon('arrow-up', 'sm') + ' Unarchive' : icon('arrow-down', 'sm') + ' Archive'}</button>
        <button type="button" role="menuitem" id="sp-leave-group">${icon('log-out', 'sm')} Leave group</button>
        <button type="button" role="menuitem" id="sp-delete-group" style="color:var(--neg)">${icon('trash', 'sm')} Delete group</button>
      </div>

      ${balancesBlock}

      <div class="tabs" role="tablist" aria-label="Group sections">
        <button type="button" class="tab ${activePanel === 'expenses' ? 'is-active' : ''}" role="tab" data-panel="expenses" aria-selected="${activePanel === 'expenses'}">Expenses</button>
        <button type="button" class="tab ${activePanel === 'balances' ? 'is-active' : ''}" role="tab" data-panel="balances" aria-selected="${activePanel === 'balances'}">Balances</button>
        <button type="button" class="tab ${activePanel === 'activity' ? 'is-active' : ''}" role="tab" data-panel="activity" aria-selected="${activePanel === 'activity'}">Activity</button>
      </div>

      <div class="panel ${activePanel === 'expenses' ? 'is-active' : ''}" id="panel-expenses" role="tabpanel">
        <div id="expenses-list"></div>
        <div id="expenses-pagination" class="pagination"></div>
      </div>

      <div class="panel ${activePanel === 'balances' ? 'is-active' : ''}" id="panel-balances" role="tabpanel">
        <div class="balances-block" style="max-width:none">
          <div class="balances-block-head"><span class="balances-block-title">Who owes who</span></div>
          ${debts.length ? debts.map(s => {
            const from = s.fromUserId === userId ? 'You' : nameOf(s.fromUserId);
            const to = s.toUserId === userId ? 'you' : nameOf(s.toUserId);
            return `
              <div class="balance-person-row">
                ${avatar(nameOf(s.fromUserId), 28, avatarOf(s.fromUserId))}
                <span class="balance-person-name">${esc(from)} ${s.fromUserId === userId ? 'owe' : 'owes'} ${esc(to)}</span>
                <span class="balance-person-amount ${s.toUserId === userId ? 'is-pos' : ''}">${formatMoney(s.amount, currency)}</span>
                <button class="btn btn-secondary btn-sm settle-btn"
                  data-from="${s.fromUserId}" data-to="${s.toUserId}"
                  data-from-name="${esc(s.fromUserName || nameOf(s.fromUserId))}"
                  data-to-name="${esc(s.toUserName || nameOf(s.toUserId))}">Record payment</button>
              </div>`;
          }).join('') : `<p class="meta" style="padding:16px 0">${icon('check-circle', 'sm')} All balances are settled.</p>`}
        </div>
      </div>

      <div class="panel ${activePanel === 'activity' ? 'is-active' : ''}" id="panel-activity" role="tabpanel">
        <div id="activity-list"></div>
        <div id="activity-pagination" class="pagination"></div>
      </div>`;

    document.getElementById('sp-view-members').onclick = () => viewMembersModal(members, groupId, userId);

    const menu = document.getElementById('sp-group-menu');
    document.getElementById('sp-group-menu-btn').onclick = (e) => {
      e.stopPropagation();
      menu.hidden = !menu.hidden;
    };
    document.addEventListener('click', (e) => {
      if (!e.target.closest('#sp-group-menu') && !e.target.closest('#sp-group-menu-btn')) menu.hidden = true;
    });

    const setPanel = (panel) => {
      activePanel = panel;
      document.querySelectorAll('.tab').forEach(b => {
        const active = b.dataset.panel === panel;
        b.classList.toggle('is-active', active);
        b.setAttribute('aria-selected', String(active));
      });
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('is-active'));
      document.getElementById(`panel-${panel}`)?.classList.add('is-active');
    };
    document.querySelectorAll('.tab').forEach(btn => { btn.onclick = () => setPanel(btn.dataset.panel); });
    setPanel(activePanel);

    document.getElementById('sp-settle-all')?.addEventListener('click', () => settleAll(groupId, userId, myDebts));
    document.getElementById('sp-add-expense').onclick = () => addExpenseModal(groupId, eligibleMembers, userId, null, group.currency);

    const memberCount = Math.max(eligibleMembers.length, 1);
    const shareOf = (e) => {
      if (e.splitType === 'EQUAL') return Number(e.amount) / memberCount;
      if (e.myShare != null) return Number(e.myShare); // supplied by a future API
      return null;
    };

    const renderExpenses = (exps) => {
      const list = document.getElementById('expenses-list');
      if (!list) return;
      const settlements = exps.filter(e => String(e.splitType).toUpperCase() === 'SETTLEMENT');
      const regular = exps.filter(e => String(e.splitType).toUpperCase() !== 'SETTLEMENT');

      const expenseHtml = regular.map(e => {
        const share = shareOf(e);
        const mine = e.paidBy === userId ? `You paid` : `${nameOf(e.paidBy)} paid`;
        return `
          <div class="expense-row" data-exp='${esc(JSON.stringify({ ...e, paidByName: nameOf(e.paidBy) }))}' role="button" tabindex="0">
            <span class="row-icon">${icon('receipt', 'sm')}</span>
            <div class="expense-row-main">
              <div class="expense-row-title">${esc(e.description || 'Expense')}</div>
              <div class="expense-row-meta">
                <span>${esc(mine)}</span>
                ${chip(splitTypeLabel(e.splitType), 'neutral')}
                ${e.receiptUrl ? `<button type="button" class="attach-btn" data-receipt="${esc(e.receiptUrl)}" aria-label="Receipt attached" title="Receipt attached">${icon('paperclip', 'xs')}</button>` : ''}
              </div>
            </div>
            <div class="expense-row-amount">
              <span class="expense-row-total">${formatMoney(e.amount, e.currency || currency)}</span>
              ${share != null ? `<span class="expense-row-share">Your share ${formatMoney(share, e.currency || currency)}</span>` : ''}
            </div>
          </div>`;
      }).join('');

      const settlementHtml = settlements.length ? `
        <div class="settlement-group">
          <div class="settlement-group-title">Settlements</div>
          ${settlements.map(e => `
            <div class="expense-row payment-row" data-exp='${esc(JSON.stringify({ ...e, paidByName: nameOf(e.paidBy) }))}' role="button" tabindex="0">
              <span class="row-icon">${icon('arrow-right', 'sm')}</span>
              <div class="expense-row-main">
                <div class="expense-row-title">${esc(e.description || 'Payment')}</div>
                <div class="expense-row-meta">${esc(formatDate(e.createdAt || e.expenseDate))}</div>
              </div>
              <div class="expense-row-amount"><span class="expense-row-total">${formatMoney(e.amount, e.currency || currency)}</span></div>
            </div>`).join('')}
        </div>` : '';

      list.innerHTML = regular.length || settlements.length
        ? expenseHtml + settlementHtml
        : emptyState('receipt', 'No expenses yet', 'Add the first shared cost for this group.');

      list.querySelectorAll('.expense-row').forEach(row => {
        const open = () => openExpenseDetailsModal(JSON.parse(row.dataset.exp), groupId, userId, eligibleMembers);
        row.onclick = (e) => { if (e.target.closest('[data-receipt]')) return; open(); };
        row.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); open(); } };
      });
      list.querySelectorAll('[data-receipt]').forEach(btn => {
        btn.onclick = (e) => { e.stopPropagation(); window.openReceiptLightbox(btn.dataset.receipt); };
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
      const pager = document.getElementById('expenses-pagination');
      if (!pager || expensesTotal <= 1) { if (pager) pager.innerHTML = ''; return; }
      let html = `<button class="btn-icon" id="ep-prev" ${expensesPage === 0 ? 'disabled' : ''} aria-label="Previous page">${icon('chevron-left', 'sm')}</button>`;
      let startPage = Math.max(0, expensesPage - 2);
      let endPage = Math.min(expensesTotal - 1, startPage + 4);
      if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
      for (let i = startPage; i <= endPage; i++) {
        html += `<button class="${i === expensesPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`;
      }
      html += `<button class="btn-icon" id="ep-next" ${expensesPage === expensesTotal - 1 ? 'disabled' : ''} aria-label="Next page">${icon('chevron-right', 'sm')}</button>`;
      pager.innerHTML = html;
      if (expensesPage > 0) document.getElementById('ep-prev').onclick = () => { expensesPage--; loadGroupExpenses(); };
      if (expensesPage < expensesTotal - 1) document.getElementById('ep-next').onclick = () => { expensesPage++; loadGroupExpenses(); };
      pager.querySelectorAll('button[data-p]').forEach(b => {
        b.onclick = () => { expensesPage = +b.dataset.p; loadGroupExpenses(); };
      });
    };

    const renderActivity = (actList) => {
      const container = document.getElementById('activity-list');
      if (!container) return;
      container.innerHTML = actList?.length ? actList.map(a => `
        <div class="activity-item">
          <div class="activity-message">
            <span>${esc(a.message)}</span>
            ${a.activityType === 'SETTLEMENT_RECORDED' ? `<button class="btn btn-danger btn-sm revert-btn" data-id="${a.referenceId}">Revert</button>` : ''}
          </div>
          <div class="activity-meta">${esc(formatDate(a.createdAt))} · ${esc(activityTypeLabel(a.activityType))}</div>
        </div>`).join('') : '<p class="meta" style="padding:20px 0;text-align:center">No activity yet.</p>';

      container.querySelectorAll('.revert-btn').forEach(btn => {
        btn.addEventListener('click', async (e) => {
          const expenseId = e.currentTarget.dataset.id;
          if (await confirmModal('Revert settlement', 'The balances will go back to what they were before this payment.', 'Revert')) {
            try {
              await api.delete(`/upsert/groups/${groupId}/expenses/${expenseId}`, { userId });
              toast('Settlement reverted', 'success');
              loadGroupDetail(groupId, userId, { preserveView: true });
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
      const pager = document.getElementById('activity-pagination');
      if (!pager || activityTotal <= 1) { if (pager) pager.innerHTML = ''; return; }
      let html = `<button class="btn-icon" id="ap-prev" ${activityPage === 0 ? 'disabled' : ''} aria-label="Previous page">${icon('chevron-left', 'sm')}</button>`;
      let startPage = Math.max(0, activityPage - 2);
      let endPage = Math.min(activityTotal - 1, startPage + 4);
      if (endPage - startPage < 4) startPage = Math.max(0, endPage - 4);
      for (let i = startPage; i <= endPage; i++) {
        html += `<button class="${i === activityPage ? 'active' : ''}" data-p="${i}" aria-label="Page ${i + 1}">${i + 1}</button>`;
      }
      html += `<button class="btn-icon" id="ap-next" ${activityPage === activityTotal - 1 ? 'disabled' : ''} aria-label="Next page">${icon('chevron-right', 'sm')}</button>`;
      pager.innerHTML = html;
      if (activityPage > 0) document.getElementById('ap-prev').onclick = () => { activityPage--; loadGroupActivity(); };
      if (activityPage < activityTotal - 1) document.getElementById('ap-next').onclick = () => { activityPage++; loadGroupActivity(); };
      pager.querySelectorAll('button[data-p]').forEach(b => {
        b.onclick = () => { activityPage = +b.dataset.p; loadGroupActivity(); };
      });
    };

    renderExpenses(expenses);

    activityPromise.then(result => {
      activityTotal = result.totalPages || 1;
      renderActivity(result.content || []);
    }).catch(() => {
      const container = document.getElementById('activity-list');
      if (container) container.innerHTML = '<p style="color:var(--neg);padding:20px 0">Could not load activity.</p>';
    });

    wireGroupMenu(group, groupId, userId);
    wireSettlementButtons(groupId, userId);
  } catch (err) {
    el.innerHTML = `<p style="color:var(--neg)">${esc(err.message)}</p>`;
  }
}

function displayNameFromId(id) {
  return id ? `Member ${String(id).slice(0, 4)}` : 'Member';
}

function renderInvitation(el, group, groupId, userId) {
  el.innerHTML = `
    <div class="card" style="max-width:560px;text-align:center;padding:var(--s7)">
      <h2 style="margin-bottom:6px">You've been invited</h2>
      <p class="meta" style="margin-bottom:var(--s5)">Join <strong style="color:var(--text)">${esc(group.name)}</strong> to split expenses with this group.</p>
      <div style="display:flex;justify-content:center;gap:12px">
        <button class="btn btn-secondary" id="sp-decline-invite">Decline</button>
        <button class="btn btn-primary" id="sp-accept-invite">Accept invitation</button>
      </div>
    </div>`;

  document.getElementById('sp-accept-invite').onclick = async () => {
    try {
      await api.post(`/upsert/groups/${groupId}/members/${userId}/accept`);
      toast('Invitation accepted', 'success');
      loadGroupDetail(groupId, userId);
    } catch (e) { toast(e.message, 'error'); }
  };
  document.getElementById('sp-decline-invite').onclick = async () => {
    if (!(await confirmModal('Decline invitation', 'You will no longer be part of this group.', 'Decline'))) return;
    try {
      await api.post(`/upsert/groups/${groupId}/members/${userId}/reject`);
      toast('Invitation declined', 'info');
      document.getElementById('sp-back').click();
    } catch (e) { toast(e.message, 'error'); }
  };
}

async function recordSettlement(groupId, userId, btn) {
  if (!(await confirmModal('Record payment',
    `Did ${btn.dataset.fromName} pay ${btn.dataset.toName}? Balances update immediately.`, 'Record payment'))) return;
  try {
    await api.post(`/upsert/groups/${groupId}/settle`, null, {
      params: { fromUserId: btn.dataset.from, toUserId: btn.dataset.to },
    });
    toast('Payment recorded', 'success');
    await loadGroupDetail(groupId, userId, { preserveView: true });
  } catch (e) { toast(e.message, 'error'); }
}

async function settleAll(groupId, userId, myDebts) {
  if (!myDebts.length) return;
  if (!(await confirmModal('Settle all',
    `Record ${myDebts.length} payment${myDebts.length === 1 ? '' : 's'} to clear your balance in this group?`, 'Settle all'))) return;
  try {
    for (const d of myDebts) {
      await api.post(`/upsert/groups/${groupId}/settle`, null, {
        params: { fromUserId: d.fromUserId, toUserId: d.toUserId },
      });
    }
    toast('All settled', 'success');
    await loadGroupDetail(groupId, userId, { preserveView: true });
  } catch (e) { toast(e.message, 'error'); }
}

function wireSettlementButtons(groupId, userId) {
  document.querySelectorAll('.settle-btn').forEach(btn => {
    if (btn.dataset.wired) return;
    btn.dataset.wired = '1';
    btn.addEventListener('click', () => recordSettlement(groupId, userId, btn));
  });
}

function wireGroupMenu(group, groupId, userId) {
  const hide = () => { const m = document.getElementById('sp-group-menu'); if (m) m.hidden = true; };

  document.getElementById('sp-edit-group')?.addEventListener('click', () => {
    hide(); editGroupModal(group, userId);
  });

  document.getElementById('sp-archive-group')?.addEventListener('click', async () => {
    hide();
    if (!(await confirmModal(`${group.isArchived ? 'Unarchive' : 'Archive'} group`,
      group.isArchived ? 'This group will return to your active list.' : 'This group will move to your archived list.',
      group.isArchived ? 'Unarchive' : 'Archive'))) return;
    try {
      await api.put(`/upsert/groups/${groupId}/archive`, null, { params: { archive: !group.isArchived } });
      toast(group.isArchived ? 'Group unarchived' : 'Group archived', 'success');
      document.getElementById('sp-back').click();
    } catch (err) { toast(err.message, 'error'); }
  });

  document.getElementById('sp-leave-group')?.addEventListener('click', async () => {
    hide();
    if (!(await confirmModal('Leave group', 'You will stop seeing this group and its expenses.', 'Leave group'))) return;
    try {
      await api.delete(`/upsert/groups/${groupId}/members/${userId}`);
      toast('Left group', 'info');
      document.getElementById('sp-back').click();
    } catch (err) { toast(err.message, 'error'); }
  });

  document.getElementById('sp-delete-group')?.addEventListener('click', async () => {
    hide();
    if (!(await confirmModal('Delete group', 'This permanently deletes the group. Every expense must be settled first.', 'Delete group'))) return;
    try {
      await api.delete(`/upsert/groups/${groupId}`, { userId });
      toast('Group deleted', 'success');
      document.getElementById('sp-back').click();
    } catch (err) { toast(err.message, 'error'); }
  });
}

/* ── Expense details ────────────────────────────────────────────────────── */
function openExpenseDetailsModal(e, groupId, userId, members = []) {
  openModal(e.description || 'Expense', `
    <div class="modal-amount-hero">
      <div class="amount-value">${formatMoney(e.amount, e.currency || Auth.getDefaultCurrency())}</div>
      <div class="amount-name">${esc(e.description || 'Expense')}</div>
      <div class="amount-date">Paid by ${esc(displayName(e.paidByName || e.paidBy))}</div>
    </div>

    <div class="modal-detail-panel">
      <div class="modal-detail-row"><span class="detail-label">Split</span><span class="detail-value">${esc(splitTypeLabel(e.splitType))}</span></div>
      <div class="modal-detail-row"><span class="detail-label">Category</span><span class="detail-value">${esc(formatCategory(e.expenseCategory || 'OTHERS'))}</span></div>
      ${e.expenseDate || e.createdAt ? `<div class="modal-detail-row"><span class="detail-label">Date</span><span class="detail-value">${esc(formatDate(e.expenseDate || e.createdAt))}</span></div>` : ''}
      ${e.receiptUrl ? `
        <div class="modal-detail-row detail-block">
          <span class="detail-label">Receipt</span>
          <div style="margin-top:6px">
            ${e.receiptUrl.toLowerCase().endsWith('.pdf')
              ? `<button type="button" class="btn btn-secondary" onclick="window.openReceiptLightbox('${esc(e.receiptUrl)}')">${icon('document', 'sm')} Open PDF receipt</button>`
              : `<button type="button" style="padding:0;border:0;background:none" onclick="window.openReceiptLightbox('${esc(e.receiptUrl)}')"><img src="${esc(e.receiptUrl)}" alt="Receipt" style="max-width:100%;max-height:180px;border-radius:8px;border:1px solid var(--line);display:block"></button>`}
          </div>
        </div>` : ''}
    </div>

    <div style="display:flex;gap:10px;margin-top:var(--s4)">
      <button class="btn btn-secondary" id="m-edit-exp-btn" style="flex:1">${icon('edit', 'sm')} Edit</button>
      <button class="btn btn-danger" id="m-del-exp-btn" style="flex:1">${icon('trash', 'sm')} Delete</button>
    </div>
  `);

  document.getElementById('m-edit-exp-btn').onclick = async () => {
    document.querySelector('.modal-overlay').click();
    let eligible = members.filter(m => m.status === 'ACCEPTED');
    if (!eligible.length) {
      try {
        const raw = await api.get(`/upsert/groups/${groupId}/members`);
        eligible = raw.filter(m => m.status === 'ACCEPTED');
      } catch {
        toast('Could not load group members', 'error');
        return;
      }
    }
    setTimeout(() => editExpense(e, groupId, userId, eligible), 50);
  };

  document.getElementById('m-del-exp-btn').onclick = async () => {
    if (!(await confirmModal('Delete expense', 'This shared expense will be removed for everyone in the group.', 'Delete'))) return;
    try {
      await api.delete(`/upsert/groups/${groupId}/expenses/${e.id}`, { userId });
      toast('Expense deleted', 'success');
      document.querySelector('.modal-overlay').click();
      loadGroupDetail(groupId, userId, { preserveView: true });
    } catch (err) { toast(err.message, 'error'); }
  };
}

/* ── Members ────────────────────────────────────────────────────────────── */
function viewMembersModal(members, groupId, userId) {
  openModal('Group members', `
    <div style="margin-bottom:var(--s4);max-height:400px;overflow-y:auto">
      ${members.map(m => `
        <div class="member-row">
          <div class="member-name">${avatar(m.displayName || m.name, 28, m.profilePicture)}<span>${esc(m.displayName || displayName(m.name))}</span></div>
          ${m.status === 'PENDING' ? chip('Pending', 'warn') : chip('Accepted', 'pos')}
        </div>`).join('')}
    </div>
    <button class="btn btn-secondary" style="width:100%" id="vm-add-member">${icon('user-plus', 'sm')} Add member</button>
  `);

  setTimeout(() => {
    document.getElementById('vm-add-member').onclick = () => {
      document.querySelector('.modal-close').click();
      addMemberModal(groupId, userId);
    };
  }, 20);
}

function editGroupModal(group, userId) {
  openModal('Edit group', `
    <form id="eg-form">
      <div class="form-group"><label for="eg-name">Group name</label><input class="form-input" id="eg-name" required maxlength="100" value="${esc(group.name)}"></div>
      <div class="form-group"><label for="eg-desc">Description</label><textarea class="form-textarea" id="eg-desc" maxlength="300">${esc(group.description || '')}</textarea></div>
      <div class="form-group"><label for="eg-currency">Currency</label><select class="form-select" id="eg-currency">${currencyOptionHtml(group.currency || 'INR')}</select></div>
      ${modalActions('Cancel', 'Save changes')}
    </form>`, {
    onSubmit: async () => {
      await api.put(`/upsert/groups/${group.id}`, {
        name: document.getElementById('eg-name').value.trim(),
        description: document.getElementById('eg-desc').value.trim() || undefined,
        currency: document.getElementById('eg-currency').value.trim().toUpperCase(),
      });
      toast('Group updated', 'success');
      loadGroupDetail(group.id, userId);
    }
  });
}

function createGroupModal(userId) {
  openModal('New group', `
    <form id="cg-form">
      <div class="form-group"><label for="cg-name">Group name</label><input class="form-input" id="cg-name" required maxlength="100" placeholder="e.g. Office lunch crew"></div>
      <div class="form-group"><label for="cg-desc">Description (optional)</label><textarea class="form-textarea" id="cg-desc" maxlength="300"></textarea></div>
      <div class="form-group"><label for="cg-currency">Currency</label><select class="form-select" id="cg-currency">${currencyOptionHtml(Auth.getDefaultCurrency())}</select></div>
      ${modalActions('Cancel', 'Create group')}
    </form>`, {
    onSubmit: async () => {
      await api.post('/upsert/groups', {
        name: document.getElementById('cg-name').value.trim(),
        description: document.getElementById('cg-desc').value.trim() || undefined,
        createdBy: userId,
        currency: document.getElementById('cg-currency').value.trim().toUpperCase(),
      });
      toast('Group created', 'success');
      loadGroups(userId);
    }
  });
}

function addMemberModal(groupId, userId) {
  openModal('Add member', `
    <form id="am-form">
      <div class="form-group">
        <label for="am-search">Search by username or email</label>
        <input class="form-input" id="am-search" placeholder="Type at least 2 characters…" autocomplete="off" required>
        <div id="am-results" class="user-search-results" hidden></div>
        <input type="hidden" id="am-uid">
        <input type="hidden" id="am-name">
        <p id="am-selected" class="meta" style="margin-top:8px"></p>
      </div>
      ${modalActions('Cancel', 'Add member')}
    </form>`, {
    onSubmit: async () => {
      const memberId = document.getElementById('am-uid').value;
      const memberName = document.getElementById('am-name').value;
      if (!memberId) throw new Error('Select a registered user from the search results');
      await api.post(`/upsert/groups/${groupId}/members`, { userId: memberId, name: memberName });
      toast('Member added', 'success');
      loadGroupDetail(groupId, userId);
    }
  });

  const searchInput = document.getElementById('am-search');
  const resultsEl = document.getElementById('am-results');
  const selectedEl = document.getElementById('am-selected');
  let debounceTimer = null;
  let searchPage = 0;

  const performSearch = async (append = false) => {
    const q = searchInput.value.trim();
    if (q.length < 2) {
      resultsEl.hidden = true;
      resultsEl.innerHTML = '';
      return;
    }
    try {
      const limit = 8;
      const users = await api.get('/auth/users/search', { q, limit, page: searchPage });
      const loadMoreBtn = document.getElementById('am-load-more');
      if (loadMoreBtn) loadMoreBtn.remove();

      const html = users.map(u => `
        <button type="button" class="user-search-item" data-id="${esc(u.userId)}" data-name="${esc(u.username)}">
          <strong>${esc(displayName(u.username))}</strong>
          <span>${esc(u.email)}</span>
        </button>`).join('');

      if (!users.length && !append) resultsEl.innerHTML = '<div class="user-search-empty">No users found</div>';
      else if (append) resultsEl.insertAdjacentHTML('beforeend', html);
      else resultsEl.innerHTML = html;

      resultsEl.querySelectorAll('.user-search-item').forEach(btn => {
        btn.onclick = () => {
          document.getElementById('am-uid').value = btn.dataset.id;
          document.getElementById('am-name').value = btn.dataset.name;
          searchInput.value = displayName(btn.dataset.name);
          selectedEl.textContent = `Selected: ${displayName(btn.dataset.name)}`;
          resultsEl.hidden = true;
        };
      });

      if (users.length === limit) {
        resultsEl.insertAdjacentHTML('beforeend', '<button type="button" id="am-load-more" class="user-search-item" style="align-items:center;color:var(--brand)">Load more</button>');
        document.getElementById('am-load-more').onclick = () => {
          searchPage++;
          document.getElementById('am-load-more').textContent = 'Loading…';
          performSearch(true);
        };
      }
      resultsEl.hidden = false;
    } catch (e) {
      resultsEl.innerHTML = `<div class="user-search-empty">${esc(e.message)}</div>`;
      resultsEl.hidden = false;
    }
  };

  searchInput.oninput = () => {
    clearTimeout(debounceTimer);
    document.getElementById('am-uid').value = '';
    document.getElementById('am-name').value = '';
    selectedEl.textContent = '';
    searchPage = 0;
    debounceTimer = setTimeout(() => performSearch(false), 300);
  };
}

/* ── Shared expense form ────────────────────────────────────────────────── */
function splitTypeLabel(type) {
  const map = { EQUAL: 'Equal', PERCENTAGE: 'Percentage', EXACT: 'By amount', SETTLEMENT: 'Payment' };
  return map[(type || 'EQUAL').toUpperCase()] || 'Equal';
}

function activityTypeLabel(type) {
  const map = {
    GROUP_CREATED: 'Group created',
    MEMBER_ADDED: 'Member added',
    EXPENSE_ADDED: 'Expense added',
    EXPENSE_DELETED: 'Expense deleted',
    SETTLEMENT_RECORDED: 'Settlement',
  };
  return map[(type || '').toUpperCase()] || type || 'Activity';
}

function editExpense(e, groupId, userId, members) {
  addExpenseModal(groupId, members, userId, e, e.currency);
}

function addExpenseModal(groupId, members, userId, initialData = null, groupCurrency = null) {
  if (!members.length) {
    toast('Add a member before creating an expense', 'error');
    return;
  }

  const isEdit = !!(initialData && initialData.id);
  const defaultDesc = initialData ? esc(initialData.description) : '';
  const defaultAmt = initialData && initialData.amount ? initialData.amount : '';
  const defaultCur = initialData && initialData.currency ? esc(initialData.currency) : (groupCurrency || Auth.getDefaultCurrency());
  const defaultDate = initialData && (initialData.expenseDate || initialData.createdAt)
    ? esc(String(initialData.expenseDate || initialData.createdAt).split('T')[0]) : '';
  const defaultReceipt = initialData && initialData.receiptUrl ? esc(initialData.receiptUrl) : '';
  const hasReceipt = !!defaultReceipt;
  const defaultPaidBy = initialData && initialData.paidBy ? initialData.paidBy : userId;
  const defaultSplitType = initialData && initialData.splitType ? initialData.splitType : 'EQUAL';
  const defaultCategory = initialData && initialData.expenseCategory ? initialData.expenseCategory : 'OTHERS';

  openModal(isEdit ? 'Edit shared expense' : 'Add shared expense', `
    <form id="ae-form">
      <div class="form-row">
        <div class="form-group" style="flex:2"><label for="ae-desc">Description</label><input class="form-input" id="ae-desc" required maxlength="100" value="${defaultDesc}" placeholder="e.g. Dinner, groceries"></div>
        <div class="form-group" style="flex:1"><label for="ae-amt">Amount</label><input class="form-input" id="ae-amt" type="text" inputmode="decimal" required value="${defaultAmt}" placeholder="0.00"></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="ae-paid">Paid by</label>
          <select class="form-select" id="ae-paid" required>
            ${members.map(m => `<option value="${m.userId}" ${defaultPaidBy === m.userId ? 'selected' : ''}>${esc(m.displayName || displayName(m.name))}</option>`).join('')}
          </select>
        </div>
        <div class="form-group"><label for="ae-currency">Currency</label><select class="form-select" id="ae-currency" required>${currencyOptionHtml(defaultCur)}</select></div>
        <div class="form-group"><label for="ae-date">Date</label><input class="form-input" id="ae-date" type="date" value="${defaultDate}"></div>
      </div>
      <div class="form-row">
        <div class="form-group"><label for="ae-split-type">Split</label>
          <select class="form-select" id="ae-split-type">
            <option value="EQUAL" ${defaultSplitType === 'EQUAL' ? 'selected' : ''}>Equally</option>
            <option value="PERCENTAGE" ${defaultSplitType === 'PERCENTAGE' ? 'selected' : ''}>By percentage</option>
            <option value="EXACT" ${defaultSplitType === 'EXACT' ? 'selected' : ''}>By exact amount</option>
          </select>
        </div>
        <div class="form-group"><label for="ae-cat">Category</label>
          <select class="form-select" id="ae-cat">${categoryOptions(EXPENSE_CATS, defaultCategory)}</select>
        </div>
      </div>
      <div id="ae-split-details" hidden></div>
      <div class="form-group" style="margin-top:4px">
        <label>Receipt or bill</label>
        <div id="ae-receipt-preview" class="receipt-preview-wrap" style="display:${hasReceipt ? 'block' : 'none'}">
          ${hasReceipt ? `<img src="${defaultReceipt}" alt="Receipt"><button type="button" class="receipt-remove" id="ae-receipt-remove" aria-label="Remove receipt">×</button>` : ''}
        </div>
        <label id="ae-receipt-upload" class="receipt-upload-zone" style="display:${hasReceipt ? 'none' : 'flex'}" for="ae-receipt-file">
          ${icon('upload', 'sm')}
          <span class="upload-label">Choose a receipt</span>
          <span class="upload-hint">Image or PDF, up to 10 MB</span>
          <input type="file" id="ae-receipt-file" accept="image/*,application/pdf">
        </label>
        <input type="hidden" id="ae-receipt-url" value="${defaultReceipt}">
      </div>
      ${modalActions('Cancel', isEdit ? 'Save changes' : 'Add expense')}
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
        submitBtn.innerHTML = '<span class="spinner"></span> Uploading…';
        try {
          const fd = new FormData();
          fd.append('file', fileInput.files[0]);
          const upRes = await api.upload(`/receipt/upload/${userId}`, fd);
          if (upRes && upRes.url) document.getElementById('ae-receipt-url').value = upRes.url;
        } catch (e) {
          toast('Could not upload the receipt: ' + e.message, 'error');
          submitBtn.disabled = false;
          submitBtn.textContent = originalText;
          return;
        }
        submitBtn.textContent = originalText;
      }

      const payload = {
        description: document.getElementById('ae-desc').value.trim(),
        amount,
        currency: document.getElementById('ae-currency').value.trim().toUpperCase(),
        paidBy: document.getElementById('ae-paid').value,
        splitType,
        expenseCategory: document.getElementById('ae-cat')?.value || 'OTHERS',
        receiptUrl: document.getElementById('ae-receipt-url').value || null,
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
      if (isoDate) payload.expenseDate = isoDate;

      if (splitType === 'PERCENTAGE' || splitType === 'EXACT') {
        const splitDetails = members.map(m => ({
          userId: m.userId,
          userName: m.name,
          value: parseFloat(document.getElementById(`ae-split-${m.userId}`).value) || 0,
        }));
        const total = splitDetails.reduce((sum, d) => sum + d.value, 0);
        if (splitType === 'PERCENTAGE' && Math.abs(total - 100) > 0.0001) {
          throw new Error('Percentages must add up to 100%');
        }
        if (splitType === 'EXACT' && Math.abs(total - amount) > 0.0001) {
          throw new Error(`Exact amounts must add up to ${formatMoney(amount, payload.currency)}`);
        }
        payload.splitDetails = splitDetails;
      }

      if (isEdit) {
        payload.id = initialData.id;
        await api.put(`/upsert/groups/${groupId}/expenses/${initialData.id}`, payload);
        toast('Saved', 'success');
      } else {
        await api.post(`/upsert/groups/${groupId}/expenses`, payload);
        toast('Expense added', 'success');
      }
      loadGroupDetail(groupId, userId);
    }
  });

  const splitTypeSelect = document.getElementById('ae-split-type');
  const splitDetailsEl = document.getElementById('ae-split-details');
  const amountInput = document.getElementById('ae-amt');

  document.getElementById('ae-receipt-remove')?.addEventListener('click', () => {
    document.getElementById('ae-receipt-preview').style.display = 'none';
    document.getElementById('ae-receipt-upload').style.display = 'flex';
    document.getElementById('ae-receipt-url').value = '';
    const fileInput = document.getElementById('ae-receipt-file');
    if (fileInput) fileInput.value = '';
  });

  const receiptFile = document.getElementById('ae-receipt-file');
  if (receiptFile) {
    receiptFile.addEventListener('change', () => {
      const file = receiptFile.files[0];
      if (!file) return;
      const preview = document.getElementById('ae-receipt-preview');
      const uploadZone = document.getElementById('ae-receipt-upload');
      const isPdf = file.type === 'application/pdf';
      const isImage = file.type.startsWith('image/');

      let previewContent = '';
      let objectUrl = null;

      if (isImage) {
        objectUrl = URL.createObjectURL(file);
        previewContent = `<img src="${objectUrl}" alt="Receipt preview">`;
      } else if (isPdf) {
        previewContent = `<div class="receipt-pdf-preview">${icon('document', 'lg')}<span>${esc(file.name)}</span><small>PDF document</small></div>`;
      } else {
        previewContent = `<div class="receipt-file-preview">${icon('file', 'lg')}<span>${esc(file.name)}</span><small>${esc(file.type || 'File')}</small></div>`;
      }

      preview.innerHTML = `${previewContent}<button type="button" class="receipt-remove" id="ae-receipt-remove" aria-label="Remove receipt">×</button>`;
      preview.style.display = 'block';
      uploadZone.style.display = 'none';
      document.getElementById('ae-receipt-url').value = '';

      document.getElementById('ae-receipt-remove').onclick = () => {
        receiptFile.value = '';
        if (objectUrl) URL.revokeObjectURL(objectUrl);
        document.getElementById('ae-receipt-preview').style.display = 'none';
        document.getElementById('ae-receipt-upload').style.display = 'flex';
        document.getElementById('ae-receipt-url').value = '';
      };
    });
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
      summary.textContent = `Total: ${total.toFixed(2)}% ${ok ? '' : '(must equal 100%)'}`;
      summary.style.color = ok ? 'var(--pos)' : 'var(--neg)';
    } else {
      const ok = Math.abs(total - amount) <= 0.0001;
      summary.textContent = `Total: ${formatMoney(total)} ${ok ? '' : `(must equal ${formatMoney(amount)})`}`;
      summary.style.color = ok ? 'var(--pos)' : 'var(--neg)';
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
            <span>${esc(m.displayName || displayName(m.name))}</span>
            <div class="split-detail-input-wrap">
              <input class="form-input split-detail-input" id="ae-split-${m.userId}"
                type="text" inputmode="decimal" value="${defaultVal}" required>
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
        if (dirtyFlags[member.userId]) dirtySum += (parseFloat(document.getElementById(`ae-split-${member.userId}`).value) || 0);
        else cleanCount++;
      });

      if (cleanCount > 0) {
        const remainder = Math.max(0, target - dirtySum);
        const perClean = Math.floor((remainder / cleanCount) * 10000) / 10000;
        members.forEach(member => {
          if (!dirtyFlags[member.userId]) document.getElementById(`ae-split-${member.userId}`).value = perClean;
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
      inputEl.addEventListener('input', () => { dirtyFlags[m.userId] = true; runAdjustment(); });
    });

    runAdjustment();

    amountInput.oninput = () => {
      if (splitTypeSelect.value === 'EXACT') runAdjustment();
    };
  }

  splitTypeSelect.onchange = renderSplitDetails;
}
