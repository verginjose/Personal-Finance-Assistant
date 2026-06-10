import { api, Auth, toast } from '../utils/api.js';
import { esc, pageHeader, emptyState, formatCurrency, formatDate, confirmModal } from '../utils/ui.js';

export async function renderSubscriptions(container) {
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Subscription Tracker', 'Detect recurring charges draining your wallet')}
    <div id="subs-grid" class="fade-up"></div>`;

  await loadSubscriptions(userId);

  async function loadSubscriptions(uid) {
    const grid = document.getElementById('subs-grid');
    grid.innerHTML = '<div class="spinner-center"><span class="spinner"></span></div>';
    try {
      const data = await api.get('/upsert/subscriptions', { userId: uid });
      if (!data?.length) {
        grid.innerHTML = `<div class="card">${emptyState('check-circle', 'No subscriptions detected', 'Mark transactions as recurring to track them here.')}</div>`;
        return;
      }
      grid.innerHTML = `<div class="sub-grid">${data.map(subCard).join('')}</div>`;
      data.forEach(sub => {
        document.getElementById(`dismiss-${sub.id}`)?.addEventListener('click', () => dismissSub(sub.id, uid));
      });
    } catch (err) { toast(err.message, 'error'); }
  }

  async function dismissSub(id, uid) {
    if (!(await confirmModal('Dismiss Subscription', 'Dismiss this subscription alert?', 'Dismiss'))) return;
    try {
      await api.delete(`/upsert/subscriptions/${id}/deactivate`, { userId: uid });
      toast('Subscription dismissed', 'success');
      await loadSubscriptions(uid);
    } catch (err) { toast(err.message, 'error'); }
  }
}

function subCard(sub) {
  const color = sub.daysUntilCharge <= 3 ? 'var(--accent)' :
                sub.daysUntilCharge <= 7 ? 'var(--accent-y)' : 'var(--accent-g)';
  const urgency = sub.daysUntilCharge === 0 ? 'Due today' :
                  sub.daysUntilCharge === 1 ? '1 day' :
                  `${sub.daysUntilCharge} days`;
  return `
    <div class="card sub-card" style="--sub-color:${color}">
      <div style="display:flex;justify-content:space-between;align-items:flex-start">
        <div>
          <h3 style="font-weight:700;font-size:1.05rem;margin-bottom:4px">${esc(sub.name)}</h3>
          <span class="badge badge-info">${esc(sub.period)}</span>
        </div>
        <div class="sub-urgency" style="color:${color}">${urgency}</div>
      </div>
      <div style="margin-top:16px;display:flex;justify-content:space-between;align-items:center">
        <div>
          <div style="font-size:1.4rem;font-weight:800">${formatCurrency(sub.amount, sub.currency)}</div>
          <div style="font-size:.8rem;color:var(--text-muted);margin-top:2px">Next: ${formatDate(sub.nextChargeDate)}</div>
        </div>
        <button id="dismiss-${sub.id}" class="btn btn-secondary btn-sm">Dismiss</button>
      </div>
    </div>`;
}
