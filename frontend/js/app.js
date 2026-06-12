/* ═══════════════════════════════════════════════════════════════════════════
   app.js — SPA Router + Shell
   ═══════════════════════════════════════════════════════════════════════════ */
import { Auth, SseManager, api } from './utils/api.js?v=18';
import { icon } from './utils/icons.js?v=18';
import { renderAuth }          from './views/auth.js?v=18';
import { renderDashboard }     from './views/dashboard.js?v=18';
import { renderTransactions }  from './views/transactions.js?v=18';
import { renderBillScanner }   from './views/bill-scanner.js?v=18';
import { renderSplit }         from './views/split.js?v=18';
import { renderAnalytics }     from './views/analytics.js?v=18';
import { renderProfile }       from './views/profile.js?v=18';
import { renderSubscriptions } from './views/subscriptions.js?v=18';
import { renderGoals }         from './views/goals.js?v=18';

const NAV_ITEMS = [
  { id: 'dashboard',      icon: 'dashboard',      label: 'Dashboard' },
  { id: 'transactions',   icon: 'transactions',   label: 'Transactions' },
  { id: 'bill-scanner',   icon: 'bill-scanner',   label: 'Bill Scanner' },
  { id: 'split',          icon: 'split',          label: 'Split Expenses' },
  { id: 'analytics',      icon: 'analytics',      label: 'Analytics' },
  { id: 'subscriptions',  icon: 'subscriptions',  label: 'Subscriptions' },
  { id: 'goals',          icon: 'goals',          label: 'Goals & Budgets' },
  { id: 'profile',        icon: 'profile',        label: 'Profile' },
];

let currentView = 'dashboard';

function renderShell() {
  const root = document.getElementById('app');

  if (!Auth.isLoggedIn()) {
    SseManager.disconnect();
    root.innerHTML = '<div id="view-root"></div>';
    renderAuth(document.getElementById('view-root'));
    return;
  }

  SseManager.connect();

  const email = Auth.getEmail() || 'User';
  const initial = email[0].toUpperCase();

  let profilePicHtml = `<div class="sidebar-user-avatar" id="shell-avatar">${initial}</div>`;
  
  root.innerHTML = `
    <div id="app-shell">
      <div class="sidebar-overlay" id="sidebar-overlay"></div>
      <aside class="sidebar" id="sidebar">
        <div class="logo">${icon('logo', 'sm')} PFA</div>
        <nav id="sidebar-nav" aria-label="Main navigation">
          ${NAV_ITEMS.map(n => `
            <a href="#" data-view="${n.id}" class="${n.id === currentView ? 'active' : ''}" aria-current="${n.id === currentView ? 'page' : 'false'}">
              <span class="icon">${icon(n.icon, 'sm')}</span>${n.label}
            </a>`).join('')}
        </nav>
        <div class="sidebar-user" id="sidebar-user" role="button" tabindex="0" aria-label="Open profile">
          ${profilePicHtml}
          <div class="sidebar-user-info">
            <div class="sidebar-user-email">${email}</div>
            <div class="sidebar-user-role">Account</div>
          </div>
        </div>
        <div class="sidebar-footer">Personal Finance Assistant v2.0</div>
      </aside>
      <div class="main-content">
        <div class="mobile-header">
          <button id="menu-toggle" aria-label="Open menu">${icon('menu')}</button>
          <span class="mobile-logo">PFA</span>
          <span></span>
        </div>
        <div id="view-root"></div>
      </div>
      
      <!-- Mobile Bottom Navigation -->
      <nav class="bottom-nav" id="bottom-nav">
        <a href="#" data-view="dashboard" class="bottom-nav-item ${currentView === 'dashboard' ? 'active' : ''}">
          ${icon('dashboard')}
          <span>Home</span>
        </a>
        <a href="#" data-view="transactions" class="bottom-nav-item ${currentView === 'transactions' ? 'active' : ''}">
          ${icon('transactions')}
          <span>Activity</span>
        </a>
        <div class="bottom-nav-fab-container">
          <button id="mobile-fab" class="bottom-nav-fab" aria-label="Quick Actions">
            ${icon('plus')}
          </button>
        </div>
        <a href="#" data-view="split" class="bottom-nav-item ${currentView === 'split' ? 'active' : ''}">
          ${icon('split')}
          <span>Split</span>
        </a>
        <a href="#" id="mobile-more-btn" class="bottom-nav-item">
          ${icon('menu')}
          <span>More</span>
        </a>
      </nav>
      
      <!-- Mobile More Menu (Bottom Sheet) -->
      <div class="bottom-sheet-overlay" id="more-menu-overlay"></div>
      <div class="bottom-sheet" id="more-menu-sheet">
        <div class="bottom-sheet-handle"></div>
        <h3 style="padding: 0 20px; margin-bottom: 10px;">More</h3>
        <div class="more-menu-list">
          <a href="#" data-view="analytics" class="more-menu-item">${icon('analytics')} Analytics</a>
          <a href="#" data-view="goals" class="more-menu-item">${icon('goals')} Goals & Budgets</a>
          <a href="#" data-view="subscriptions" class="more-menu-item">${icon('subscriptions')} Subscriptions</a>
          <a href="#" data-view="profile" class="more-menu-item">${icon('profile')} Profile</a>
        </div>
      </div>
      
      <!-- Global Action Sheet (Bottom Sheet) -->
      <div class="bottom-sheet-overlay" id="action-sheet-overlay"></div>
      <div class="bottom-sheet" id="action-sheet">
        <div class="bottom-sheet-handle"></div>
        <h3 style="padding: 0 20px; margin-bottom: 10px;">Quick Actions</h3>
        <div class="more-menu-list">
          <a href="#" data-view="bill-scanner" class="more-menu-item" id="action-scan">${icon('bill-scanner')} Scan Bill</a>
          <a href="#" class="more-menu-item" id="action-add-expense">${icon('transactions')} Add Expense</a>
        </div>
      </div>
    </div>`;

  document.getElementById('sidebar-nav').querySelectorAll('a').forEach(a => {
    a.onclick = (e) => { e.preventDefault(); navigateTo(a.dataset.view); };
  });
  
  // Bind Bottom Nav
  document.querySelectorAll('#bottom-nav .bottom-nav-item[data-view]').forEach(a => {
    a.onclick = (e) => { e.preventDefault(); navigateTo(a.dataset.view); };
  });
  document.querySelectorAll('.more-menu-item[data-view]').forEach(a => {
    a.onclick = (e) => { 
      e.preventDefault(); 
      closeBottomSheets();
      navigateTo(a.dataset.view); 
    };
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

  // Bottom Sheets Logic
  const moreMenuOverlay = document.getElementById('more-menu-overlay');
  const moreMenuSheet = document.getElementById('more-menu-sheet');
  const actionOverlay = document.getElementById('action-sheet-overlay');
  const actionSheet = document.getElementById('action-sheet');
  
  function closeBottomSheets() {
    moreMenuOverlay.classList.remove('open');
    moreMenuSheet.classList.remove('open');
    actionOverlay.classList.remove('open');
    actionSheet.classList.remove('open');
  }
  
  document.getElementById('mobile-more-btn').onclick = (e) => {
    e.preventDefault();
    moreMenuOverlay.classList.add('open');
    moreMenuSheet.classList.add('open');
  };
  
  document.getElementById('mobile-fab').onclick = (e) => {
    e.preventDefault();
    actionOverlay.classList.add('open');
    actionSheet.classList.add('open');
  };
  
  moreMenuOverlay.onclick = closeBottomSheets;
  actionOverlay.onclick = closeBottomSheets;
  
  document.getElementById('action-add-expense').onclick = (e) => {
    e.preventDefault();
    closeBottomSheets();
    navigateTo('transactions');
    setTimeout(() => {
        const addBtn = document.getElementById('t-add-btn');
        if (addBtn) addBtn.click();
    }, 100);
  };

  renderView();

  // Load profile picture async
  api.get('/auth/me').then(me => {
    if (me.profilePicture) {
      const sidebarAvatar = document.getElementById('shell-avatar');
      if (sidebarAvatar) {
        sidebarAvatar.style.backgroundImage = `url('${me.profilePicture}')`;
        sidebarAvatar.style.backgroundSize = 'cover';
        sidebarAvatar.style.backgroundPosition = 'center';
        sidebarAvatar.textContent = '';
      }
    }
  }).catch(() => {});
}

export function navigateTo(view) {
  currentView = view;
  
  // Update sidebar
  document.querySelectorAll('#sidebar-nav a').forEach(a => {
    const active = a.dataset.view === view;
    a.classList.toggle('active', active);
    a.setAttribute('aria-current', active ? 'page' : 'false');
  });
  
  // Update bottom nav
  document.querySelectorAll('#bottom-nav .bottom-nav-item[data-view]').forEach(a => {
    const active = a.dataset.view === view;
    a.classList.toggle('active', active);
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
