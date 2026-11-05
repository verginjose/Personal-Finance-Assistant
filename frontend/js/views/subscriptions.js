import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, pageHeader, emptyState, formatMoney, formatUpcoming,
  confirmModal, openModal, modalActions, avatar, asDate, daysUntilDate,
} from '../utils/ui.js';

const PERIOD_MONTHS = { DAILY: 30, WEEKLY: 4.345, MONTHLY: 1, YEARLY: 1 / 12 };
let sortKey = 'next';

export async function renderSubscriptions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Subscriptions', '')}
    <div class="subs-summary" id="subs-summary"></div>
    <div id="subs-month"></div>
    <div id="subs-grid"></div>`;

  await loadSubscriptions(userId);

  async function loadSubscriptions(uid) {
    const grid = document.getElementById('subs-grid');
    const summary = document.getElementById('subs-summary');
    const month = document.getElementById('subs-month');
    if (!grid) return;
    grid.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
    try {
      const data = await api.get('/upsert/subscriptions', {
        userId: uid,
        tzOffsetMinutes: -new Date().getTimezoneOffset(),
      });
      if (!data?.length) {
        if (summary) summary.innerHTML = '';
        if (month) month.innerHTML = '';
        grid.innerHTML = emptyState('repeat', 'No subscriptions detected',
          'Mark a transaction as recurring to track its next charge here.');
        return;
      }

      const currency = Auth.getDefaultCurrency();
      const monthlyTotal = data.reduce((sum, s) => sum + monthly(s), 0);
      const yearlyTotal = monthlyTotal * 12;

      if (summary) {
        summary.innerHTML = `
          <strong>${formatMoney(monthlyTotal, currency)}</strong>
          <span class="subs-summary-unit">a month · ${formatMoney(yearlyTotal, currency)} a year · ${data.length} recurring charge${data.length === 1 ? '' : 's'}</span>`;
      }

      if (month) month.innerHTML = monthStrip(data, currency);

      // Count down from the date itself: the stored daysUntilCharge is only a
      // snapshot from the nightly job, and it drifts between runs.
      const upcoming = data
        .map(s => ({ sub: s, days: daysUntilDate(s.nextChargeDate) }))
        .filter(x => x.days != null && x.days >= 0 && x.days <= 7)
        .sort((a, b) => a.days - b.days);

      const sorted = sortSubs(data);
      grid.innerHTML = `
        ${upcoming.length ? `
          <div class="upcoming-strip">
            ${icon('calendar', 'sm')}
            <span><strong>Due this week</strong> · ${upcoming.map(({ sub, days }) =>
              `${esc(sub.name)} ${days === 0 ? 'today' : `in ${days} day${days === 1 ? '' : 's'}`}`
            ).join(', ')}</span>
          </div>` : ''}
        <div class="subs-columns">
          <span class="card-subtitle">${sortKey === 'next' ? 'Next charge first' : 'Most expensive first'}</span>
          <div class="subs-sort">
            <button type="button" class="sort-btn" data-sort="next" aria-pressed="${sortKey === 'next'}">Next charge</button>
            <button type="button" class="sort-btn" data-sort="monthly" aria-pressed="${sortKey === 'monthly'}">Monthly cost</button>
          </div>
        </div>
        <div class="subs-list">
          ${sorted.map(sub => subRow(sub)).join('')}
        </div>`;

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
    document.getElementById(`sub-row-${sub.id}`)?.addEventListener('click', () => editSub(sub, uid));
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

/**
 * The month strip: every day of the current month, with a mark where a
 * subscription is due. It is a calendar for the next 31 days, nothing more.
 */
function monthStrip(data, currency) {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth();
  const days = new Date(year, month + 1, 0).getDate();
  const today = now.getDate();

  const byDay = new Map();
  data.forEach((sub) => {
    if (!sub.nextChargeDate) return;
    // Parse as a local date, exactly as the relative label does; `new Date('2026-11-04')`
    // would be UTC midnight and can land on the previous day west of Greenwich.
    const d = asDate(sub.nextChargeDate);
    if (!d) return;
    if (d.getFullYear() !== year || d.getMonth() !== month) return;
    const day = d.getDate();
    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day).push(sub);
  });

  const cells = Array.from({ length: 31 }, (_, i) => {
    const day = i + 1;
    if (day > days) return '<span class="month-strip-day is-empty" aria-hidden="true"></span>';
    const charges = byDay.get(day) || [];
    const names = charges.map(s => `${s.name} ${formatMoney(Number(s.amount) || 0, s.currency || currency)}`).join(', ');
    const title = charges.length ? `${day}: ${names}` : `Day ${day}: no charge`;
    return `<span class="month-strip-day${charges.length ? ' has-charge' : ''}${day === today ? ' is-today' : ''}"
      title="${esc(title)}" aria-label="${esc(title)}">${day}</span>`;
  }).join('');

  const monthName = now.toLocaleDateString('en-IN', { month: 'long' });
  return `
    <div class="month-strip">
      <div class="month-strip-head">
        <span>Charges in ${esc(monthName)}</span>
        <span>${byDay.size ? `${byDay.size} day${byDay.size === 1 ? '' : 's'}` : 'None this month'}</span>
      </div>
      <div class="month-strip-days">${cells}</div>
    </div>`;
}

function subRow(sub) {
  const cost = sub.currency || Auth.getDefaultCurrency();
  return `
    <div class="subs-card-row" data-sub-id="${sub.id}">
      <button type="button" class="subs-card-open" id="sub-row-${sub.id}" aria-label="Edit ${esc(sub.name)}">
        ${avatar(sub.name, 32)}
        <span class="subs-card-main">
          <span class="subs-card-name">${esc(sub.name)}</span>
          <span class="subs-card-meta">${esc(titleCase(sub.period))} · ${esc(formatUpcoming(sub.nextChargeDate, daysUntilDate(sub.nextChargeDate)))}</span>
        </span>
        <span class="subs-card-amount">
          <strong>${formatMoney(sub.amount, cost)} <span class="subs-card-per">per charge</span></strong>
        </span>
      </button>
      <span class="overflow-wrap">
        <button type="button" class="sub-row-menu-btn" id="sub-menu-btn-${sub.id}" aria-label="Actions for ${esc(sub.name)}" aria-haspopup="menu">${icon('more-vertical', 'sm')}</button>
        <div class="overflow-menu sub-row-menu" id="sub-menu-${sub.id}" role="menu" hidden>
          <button type="button" role="menuitem" id="sub-edit-${sub.id}">Edit</button>
          <button type="button" role="menuitem" id="sub-dismiss-${sub.id}">Not a subscription</button>
        </div>
      </span>
    </div>`;
}
