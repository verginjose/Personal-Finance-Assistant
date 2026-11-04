import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, pageHeader, emptyState, formatMoney, formatDate, formatUpcoming,
  confirmModal, openModal, modalActions, avatar, chip,
} from '../utils/ui.js';

const PERIOD_MONTHS = { DAILY: 30, WEEKLY: 4.345, MONTHLY: 1, YEARLY: 1 / 12 };
let sortKey = 'next';

export async function renderSubscriptions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Subscriptions', '')}
    <div class="subs-summary" id="subs-summary"></div>
    <div id="subs-grid"></div>`;

  await loadSubscriptions(userId);

  async function loadSubscriptions(uid) {
    const grid = document.getElementById('subs-grid');
    const summary = document.getElementById('subs-summary');
    if (!grid) return;
    grid.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
    try {
      const data = await api.get('/upsert/subscriptions', { userId: uid });
      if (!data?.length) {
        if (summary) summary.innerHTML = '';
        grid.innerHTML = emptyState('check-circle', 'No subscriptions detected',
          'Mark transactions as recurring to track them here.');
        return;
      }

      const currency = Auth.getDefaultCurrency();
      const monthlyTotal = data.reduce((sum, s) => sum + monthly(s), 0);
      const yearlyTotal = monthlyTotal * 12;

      if (summary) {
        summary.innerHTML = `
          <strong>${formatMoney(monthlyTotal, currency)} a month</strong>
          <span>${formatMoney(yearlyTotal, currency)} a year · ${data.length} recurring charge${data.length === 1 ? '' : 's'}</span>`;
      }

      const upcoming = data
        .filter(s => s.daysUntilCharge != null && s.daysUntilCharge >= 0 && s.daysUntilCharge <= 7)
        .sort((a, b) => a.daysUntilCharge - b.daysUntilCharge);

      const sorted = sortSubs(data);
      grid.innerHTML = `
        ${upcoming.length ? `
          <div class="upcoming-strip">
            ${icon('calendar', 'sm')}
            <span><strong>Due this week</strong> · ${upcoming.map(s =>
              `${esc(s.name)} ${s.daysUntilCharge === 0 ? 'today' : `in ${s.daysUntilCharge} day${s.daysUntilCharge === 1 ? '' : 's'}`}`
            ).join(', ')}</span>
          </div>` : ''}
        <div class="table-wrap subs-desktop">
          <table class="subs-table">
            <thead><tr>
              <th>Subscription</th>
              <th>Frequency</th>
              <th style="text-align:right">Amount</th>
              <th style="text-align:right">
                <button type="button" class="sort-btn" data-sort="monthly" aria-label="Sort by monthly cost">Per month</button>
              </th>
              <th>
                <button type="button" class="sort-btn" data-sort="next" aria-label="Sort by next charge">Next charge</button>
              </th>
              <th><span class="sr-only">Actions</span></th>
            </tr></thead>
            <tbody>
              ${sorted.map(subRow).join('')}
            </tbody>
          </table>
        </div>
        <div class="subs-mobile" id="subs-mobile"></div>`;

      const mobile = document.getElementById('subs-mobile');
      if (mobile) {
        mobile.innerHTML = sorted.map(sub => `
          <button type="button" class="subs-card-row" data-sub-id="${sub.id}" style="width:100%;text-align:left">
            ${avatar(sub.name, 32)}
            <span class="subs-card-main">
              <span class="subs-card-name">${esc(sub.name)}</span>
              <span class="subs-card-meta">${esc(titleCase(sub.period))} · ${esc(formatUpcoming(sub.nextChargeDate, sub.daysUntilCharge))}</span>
            </span>
            <span class="subs-card-amount">
              <strong>${formatMoney(monthly(sub), currency)}</strong>
              <span>per month</span>
            </span>
          </button>`).join('');
        mobile.querySelectorAll('[data-sub-id]').forEach(row => {
          row.onclick = () => {
            const sub = sorted.find(s => String(s.id) === row.dataset.subId);
            if (sub) editSub(sub, uid);
          };
        });
      }

      grid.querySelectorAll('[data-sort]').forEach(btn => {
        btn.onclick = () => { sortKey = btn.dataset.sort; loadSubscriptions(uid); };
      });

      sorted.forEach(sub => wireRow(sub, uid));
    } catch (err) { toast(err.message, 'error'); }
  }

  function sortSubs(list) {
    const sorted = [...list];
    if (sortKey === 'monthly') sorted.sort((a, b) => monthly(b) - monthly(a));
    else {
      sorted.sort((a, b) => {
        const av = a.nextChargeDate ? new Date(a.nextChargeDate).getTime() : Infinity;
        const bv = b.nextChargeDate ? new Date(b.nextChargeDate).getTime() : Infinity;
        return av - bv;
      });
    }
    return sorted;
  }

  function wireRow(sub, uid) {
    document.getElementById(`sub-row-${sub.id}`)?.addEventListener('click', (e) => {
      if (e.target.closest('.sub-row-menu-btn') || e.target.closest('.sub-row-menu')) return;
      editSub(sub, uid);
    });
    const menuBtn = document.getElementById(`sub-menu-btn-${sub.id}`);
    const menu = document.getElementById(`sub-menu-${sub.id}`);
    if (menuBtn && menu) {
      menuBtn.onclick = (e) => {
        e.stopPropagation();
        document.querySelectorAll('.sub-row-menu').forEach(m => { if (m !== menu) m.hidden = true; });
        menu.hidden = !menu.hidden;
      };
      document.getElementById(`sub-edit-${sub.id}`).onclick = (e) => {
        e.stopPropagation();
        menu.hidden = true;
        editSub(sub, uid);
      };
      document.getElementById(`sub-dismiss-${sub.id}`).onclick = (e) => {
        e.stopPropagation();
        menu.hidden = true;
        dismissSub(sub, uid);
      };
    }
  }

  async function dismissSub(sub, uid) {
    if (!(await confirmModal('Stop tracking', `${sub.name} stays in your transactions; it just stops appearing here.`, 'Stop tracking'))) return;
    try {
      await api.delete(`/upsert/subscriptions/${sub.id}/deactivate`, { userId: uid });
      toast('Marked as not a subscription', 'success');
      await loadSubscriptions(uid);
    } catch (err) { toast(err.message, 'error'); }
  }

  function editSub(sub, uid) {
    const periods = ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'];
    openModal('Edit subscription', `
      <form id="edit-sub-form">
        <div class="form-group">
          <label for="s-name">Name</label>
          <input id="s-name" type="text" class="form-input" name="name" value="${esc(sub.name)}" required>
        </div>
        <div class="form-group">
          <label for="s-amount">Amount</label>
          <input id="s-amount" type="number" step="0.01" class="form-input" name="amount" value="${sub.amount}" required>
        </div>
        <div class="form-group">
          <label for="s-period">Frequency</label>
          <select id="s-period" class="form-select" name="period" required>
            ${periods.map(p => `<option value="${p}" ${p === sub.period ? 'selected' : ''}>${titleCase(p)}</option>`).join('')}
          </select>
        </div>
        ${modalActions('Cancel', 'Save changes')}
      </form>
    `, {
      onSubmit: async (form) => {
        const body = {
          name: form.name.value,
          amount: parseFloat(form.amount.value),
          period: form.period.value,
        };
        await api.put(`/upsert/subscriptions/${sub.id}`, body, { params: { userId: uid } });
        toast('Saved', 'success');
        await loadSubscriptions(uid);
      }
    });
  }
}

function monthly(sub) {
  const factor = PERIOD_MONTHS[sub.period] ?? 1;
  return Number(sub.amount || 0) * factor;
}

function titleCase(value) {
  return String(value || '').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

function frequencyChip(period) {
  const tone = period === 'YEARLY' ? 'neutral' : period === 'DAILY' ? 'warn' : 'brand';
  return chip(titleCase(period), tone);
}

function subRow(sub) {
  const currency = sub.currency || Auth.getDefaultCurrency();
  return `
    <tr id="sub-row-${sub.id}" style="cursor:pointer">
      <td>
        <span class="subs-name">${avatar(sub.name, 32)}<span>${esc(sub.name)}</span></span>
      </td>
      <td>${frequencyChip(sub.period)}</td>
      <td class="subs-amount">${formatMoney(sub.amount, currency)}</td>
      <td class="subs-amount">${formatMoney(monthly(sub), currency)}</td>
      <td class="subs-when">${esc(formatUpcoming(sub.nextChargeDate, sub.daysUntilCharge))}</td>
      <td style="text-align:right">
        <span class="overflow-wrap">
          <button type="button" class="sub-row-menu-btn" id="sub-menu-btn-${sub.id}" aria-label="Actions for ${esc(sub.name)}" aria-haspopup="menu">${icon('more-vertical', 'sm')}</button>
          <div class="overflow-menu sub-row-menu" id="sub-menu-${sub.id}" role="menu" hidden>
            <button type="button" role="menuitem" id="sub-edit-${sub.id}">Edit</button>
            <button type="button" role="menuitem" id="sub-dismiss-${sub.id}">Not a subscription</button>
          </div>
        </span>
      </td>
    </tr>`;
}
