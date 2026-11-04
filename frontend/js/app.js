/* ═══════════════════════════════════════════════════════════════════════════
   app.js — SPA Router + Shell
   ═══════════════════════════════════════════════════════════════════════════ */
import { Auth, SseManager, api } from './utils/api.js?v=2026110309';
import { icon } from './utils/icons.js?v=2026110309';
import { renderAuth }          from './views/auth.js?v=2026110309';
import { esc }                 from './utils/ui.js?v=2026110309';
import { renderDashboard }     from './views/dashboard.js?v=2026110309';
import { renderTransactions }  from './views/transactions.js?v=2026110309';
import { renderBillScanner }   from './views/bill-scanner.js?v=2026110309';
import { renderSplit }         from './views/split.js?v=2026110309';
import { renderAnalytics }     from './views/analytics.js?v=2026110309';
import { renderProfile }       from './views/profile.js?v=2026110309';
import { renderSubscriptions } from './views/subscriptions.js?v=2026110309';
import { renderGoals }         from './views/goals.js?v=2026110309';

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

// Views that live behind the bottom bar's "More" sheet. The bar itself only
// has room for Home / Activity / Analytics / More, so the rest are grouped
// here — and "More" has to light up whenever one of them is open, otherwise
// the user has no indication of where they are.
const MORE_VIEWS = ['bill-scanner', 'split', 'goals', 'subscriptions', 'profile'];

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
  let savedName = Auth.getName();
  if (savedName === 'undefined') savedName = '';
  const name  = savedName  || email.split('@')[0];
  const initial = name[0].toUpperCase();

  let profilePicHtml = `<div class="sidebar-user-avatar" id="shell-avatar">${initial}</div>`;

  // Sidebar collapse is a desktop preference; remembered across reloads.
  let sidebarCollapsed = false;
  try { sidebarCollapsed = localStorage.getItem('pfa_sidebar_collapsed') === '1'; } catch { /* storage unavailable */ }

  root.innerHTML = `
    <div id="app-shell" class="${sidebarCollapsed ? 'sidebar-collapsed' : ''}">
      <aside class="sidebar" id="sidebar">
        <div class="sidebar-head">
          <div class="logo">${icon('logo', 'sm')} PFA</div>
          <button type="button" class="sidebar-collapse" id="sidebar-collapse" aria-label="Hide navigation" title="Hide navigation">${icon('chevron-left', 'sm')}</button>
        </div>
        <nav id="sidebar-nav" aria-label="Main navigation">
          ${NAV_ITEMS.map(n => `
            <a href="#" data-view="${n.id}" class="${n.id === currentView ? 'active' : ''}" aria-current="${n.id === currentView ? 'page' : 'false'}">
              <span class="icon">${icon(n.icon, 'sm')}</span>${n.label}
            </a>`).join('')}
        </nav>
        <button type="button" class="sidebar-quick-add" id="sidebar-add-txn">
          ${icon('plus', 'sm')} Add transaction
        </button>
        <div class="sidebar-user" id="sidebar-user" role="button" tabindex="0" aria-label="Open profile">
          ${profilePicHtml}
          <div class="sidebar-user-info">
            <div class="sidebar-user-name">${esc(name)}</div>
            <div class="sidebar-user-email">${esc(email)}</div>
          </div>
        </div>
        <div class="sidebar-footer">
          <span class="sidebar-online-dot"></span> PFA v2.0
        </div>
      </aside>
      <div class="main-content">
        <button type="button" class="sidebar-expand" id="sidebar-expand" aria-label="Show navigation" title="Show navigation">${icon('menu')}</button>
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
        <a href="#" data-view="analytics" class="bottom-nav-item ${currentView === 'analytics' ? 'active' : ''}">
          ${icon('analytics')}
          <span>Analytics</span>
        </a>
        <a href="#" id="mobile-more-btn" class="bottom-nav-item ${MORE_VIEWS.includes(currentView) ? 'active' : ''}" aria-label="More">
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
          <a href="#" data-view="bill-scanner" class="more-menu-item">${icon('bill-scanner')} Bill Scanner</a>
          <a href="#" data-view="split" class="more-menu-item">${icon('split')} Split Expenses</a>
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

  document.getElementById('sidebar-add-txn').onclick = openAddTransaction;

  const shell = document.getElementById('app-shell');
  const setSidebarCollapsed = (collapsed) => {
    shell.classList.toggle('sidebar-collapsed', collapsed);
    try { localStorage.setItem('pfa_sidebar_collapsed', collapsed ? '1' : '0'); } catch { /* storage unavailable */ }
  };
  document.getElementById('sidebar-collapse')?.addEventListener('click', () => setSidebarCollapsed(true));
  document.getElementById('sidebar-expand')?.addEventListener('click', () => setSidebarCollapsed(false));

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
    openAddTransaction();
  };

  updateNavActive(currentView);
  renderView();

  // Load profile picture + preferences async
  api.get('/auth/me').then(me => {
    Auth.setDefaultCurrency(me.defaultCurrency);
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
  updateNavActive(view);
  renderView();
}

function updateNavActive(view) {
  document.querySelectorAll('#sidebar-nav a').forEach(a => {
    const active = a.dataset.view === view;
    a.classList.toggle('active', active);
    a.setAttribute('aria-current', active ? 'page' : 'false');
  });

  document.querySelectorAll('#bottom-nav .bottom-nav-item[data-view]').forEach(a => {
    const active = a.dataset.view === view;
    a.classList.toggle('active', active);
    if (active) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });

  // "More" owns every view not represented by its own bar slot, so it must
  // stay highlighted while one of them is open.
  const moreBtn = document.getElementById('mobile-more-btn');
  if (moreBtn) {
    const active = MORE_VIEWS.includes(view);
    moreBtn.classList.toggle('active', active);
    if (active) moreBtn.setAttribute('aria-current', 'page');
    else moreBtn.removeAttribute('aria-current');
  }
}

function openAddTransaction() {
  navigateTo('transactions');
  // renderTransactions paints its header synchronously, so the button exists
  // on the next tick. The id is `t-add` (not `t-add-btn`).
  setTimeout(() => {
    const addBtn = document.getElementById('t-add');
    if (addBtn) addBtn.click();
  }, 100);
}

// Template strings use inline handlers like onclick="navigateTo('transactions')".
// Inline handlers resolve names in global scope, where a module-level export is
// not visible (this broke the dashboard's Add Transaction / Set a Goal buttons
// for users with no data). Expose it explicitly.
window.navigateTo = navigateTo;
window.openAddTransaction = openAddTransaction;

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
