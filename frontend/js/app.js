/* ═══════════════════════════════════════════════════════════════════════════
   app.js — SPA Router + Shell
   ═══════════════════════════════════════════════════════════════════════════ */
import { Auth } from './utils/api.js';
import { renderAuth }          from './views/auth.js';
import { renderDashboard }     from './views/dashboard.js';
import { renderTransactions }  from './views/transactions.js';
import { renderBillScanner }   from './views/bill-scanner.js';
import { renderSplit }         from './views/split.js';
import { renderAnalytics }     from './views/analytics.js';
import { renderProfile }       from './views/profile.js';
import { renderSubscriptions } from './views/subscriptions.js';
import { renderGoals }         from './views/goals.js';

const NAV_ITEMS = [
  { id: 'dashboard',      icon: '📊', label: 'Dashboard' },
  { id: 'transactions',   icon: '💳', label: 'Transactions' },
  { id: 'bill-scanner',   icon: '📄', label: 'Bill Scanner' },
  { id: 'split',          icon: '👥', label: 'Split Expenses' },
  { id: 'analytics',      icon: '📈', label: 'Analytics' },
  { id: 'subscriptions',  icon: '🧛', label: 'Subscriptions' },
  { id: 'goals',          icon: '🎯', label: 'Goals & Budgets' },
  { id: 'profile',        icon: '👤', label: 'Profile' },
];

let currentView = 'dashboard';

function renderShell() {
  const root = document.getElementById('app');

  if (!Auth.isLoggedIn()) {
    root.innerHTML = '<div id="view-root"></div>';
    renderAuth(document.getElementById('view-root'));
    return;
  }

  const email = Auth.getEmail() || 'User';
  const initial = email[0].toUpperCase();

  root.innerHTML = `
    <div id="app-shell">
      <div class="sidebar-overlay" id="sidebar-overlay"></div>
      <aside class="sidebar" id="sidebar">
        <div class="logo"><span>💰</span> PFA</div>
        <nav id="sidebar-nav" aria-label="Main navigation">
          ${NAV_ITEMS.map(n => `
            <a href="#" data-view="${n.id}" class="${n.id === currentView ? 'active' : ''}" aria-current="${n.id === currentView ? 'page' : 'false'}">
              <span class="icon" aria-hidden="true">${n.icon}</span>${n.label}
            </a>`).join('')}
        </nav>
        <div class="sidebar-user" id="sidebar-user" role="button" tabindex="0" aria-label="Open profile">
          <div class="sidebar-user-avatar">${initial}</div>
          <div class="sidebar-user-info">
            <div class="sidebar-user-email">${email}</div>
            <div class="sidebar-user-role">Account</div>
          </div>
        </div>
        <div class="sidebar-footer">Personal Finance Assistant v2.0</div>
      </aside>
      <div class="main-content">
        <div class="mobile-header">
          <button id="menu-toggle" aria-label="Open menu">☰</button>
          <span style="font-weight:700;font-family:var(--font-display);background:var(--gradient);-webkit-background-clip:text;-webkit-text-fill-color:transparent">PFA</span>
          <span></span>
        </div>
        <div id="view-root"></div>
      </div>
    </div>`;

  document.getElementById('sidebar-nav').querySelectorAll('a').forEach(a => {
    a.onclick = (e) => { e.preventDefault(); navigateTo(a.dataset.view); };
  });

  document.getElementById('sidebar-user').onclick = () => navigateTo('profile');
  document.getElementById('sidebar-user').onkeydown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); navigateTo('profile'); }
  };

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
  document.querySelectorAll('#sidebar-nav a').forEach(a => {
    const active = a.dataset.view === view;
    a.classList.toggle('active', active);
    a.setAttribute('aria-current', active ? 'page' : 'false');
  });
  document.getElementById('sidebar').classList.remove('open');
  document.getElementById('sidebar-overlay').classList.remove('open');
  renderView();
}

function renderView() {
  const root = document.getElementById('view-root');
  if (!root) return;
  root.innerHTML = '';

  const views = {
    dashboard:       renderDashboard,
    transactions:    renderTransactions,
    'bill-scanner':  renderBillScanner,
    split:           renderSplit,
    analytics:       renderAnalytics,
    subscriptions:   renderSubscriptions,
    goals:           renderGoals,
    profile:         renderProfile,
  };

  (views[currentView] || renderDashboard)(root);
}

window.addEventListener('auth-change', () => { currentView = 'dashboard'; renderShell(); });
window.addEventListener('auth-expired', () => renderShell());
document.addEventListener('DOMContentLoaded', renderShell);

export { navigateTo, NAV_ITEMS, currentView };
