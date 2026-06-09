/* ═══════════════════════════════════════════════════════════════════════════
   app.js — SPA Router + Shell
   ═══════════════════════════════════════════════════════════════════════════ */
import { Auth } from './utils/api.js';
import { renderAuth }         from './views/auth.js';
import { renderDashboard }    from './views/dashboard.js';
import { renderTransactions } from './views/transactions.js';
import { renderBillScanner }  from './views/bill-scanner.js';
import { renderSplit }        from './views/split.js';
import { renderAnalytics }    from './views/analytics.js';
import { renderProfile }      from './views/profile.js';

const NAV_ITEMS = [
  { id: 'dashboard',    icon: '📊', label: 'Dashboard' },
  { id: 'transactions', icon: '💳', label: 'Transactions' },
  { id: 'bill-scanner', icon: '📄', label: 'Bill Scanner' },
  { id: 'split',        icon: '👥', label: 'Split Expenses' },
  { id: 'analytics',    icon: '📈', label: 'Analytics' },
  { id: 'profile',      icon: '👤', label: 'Profile' },
];

let currentView = 'dashboard';

function renderShell() {
  const root = document.getElementById('app');

  if (!Auth.isLoggedIn()) {
    root.innerHTML = '<div id="view-root"></div>';
    renderAuth(document.getElementById('view-root'));
    return;
  }

  root.innerHTML = `
    <div id="app-shell">
      <div class="sidebar-overlay" id="sidebar-overlay"></div>
      <aside class="sidebar" id="sidebar">
        <div class="logo"><span>💰</span> PFA</div>
        <nav id="sidebar-nav">
          ${NAV_ITEMS.map(n => `<a href="#" data-view="${n.id}" class="${n.id===currentView?'active':''}"><span class="icon">${n.icon}</span>${n.label}</a>`).join('')}
        </nav>
        <div class="sidebar-footer">Personal Finance Assistant<br>v1.0</div>
      </aside>
      <div class="main-content">
        <div class="mobile-header">
          <button id="menu-toggle">☰</button>
          <span style="font-weight:700;background:var(--gradient);-webkit-background-clip:text;-webkit-text-fill-color:transparent">PFA</span>
          <span></span>
        </div>
        <div id="view-root"></div>
      </div>
    </div>`;

  document.getElementById('sidebar-nav').querySelectorAll('a').forEach(a => {
    a.onclick = (e) => { e.preventDefault(); navigateTo(a.dataset.view); };
  });

  document.getElementById('menu-toggle')?.addEventListener('click', () => {
    document.getElementById('sidebar').classList.toggle('open');
    document.getElementById('sidebar-overlay').classList.toggle('open');
  });
  document.getElementById('sidebar-overlay').onclick = () => {
    document.getElementById('sidebar').classList.remove('open');
    document.getElementById('sidebar-overlay').classList.remove('open');
  };

  renderView();
}

function navigateTo(view) {
  currentView = view;
  document.querySelectorAll('#sidebar-nav a').forEach(a => a.classList.toggle('active', a.dataset.view === view));
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebar-overlay').classList.remove('open');
  renderView();
}

function renderView() {
  const root = document.getElementById('view-root');
  if (!root) return;
  root.innerHTML = '';

  const views = {
    dashboard:    renderDashboard,
    transactions: renderTransactions,
    'bill-scanner': renderBillScanner,
    split:        renderSplit,
    analytics:    renderAnalytics,
    profile:      renderProfile,
  };

  (views[currentView] || renderDashboard)(root);
}

// ── Bootstrap ────────────────────────────────────────────────────────────────
window.addEventListener('auth-change', () => { currentView = 'dashboard'; renderShell(); });
window.addEventListener('auth-expired', () => renderShell());
document.addEventListener('DOMContentLoaded', renderShell);
