/* ═══════════════════════════════════════════════════════════════════════════
   app.js — Router + application shell
   ═══════════════════════════════════════════════════════════════════════════ */
import { Auth, SseManager, api, toast } from './utils/api.js';
import { icon } from './utils/icons.js';
import { renderAuth }          from './views/auth.js';
import {
  esc, avatar, applyTheme, getThemePreference, setThemePreference,
  openModal,
} from './utils/ui.js';
import { renderDashboard }     from './views/dashboard.js';
import { renderTransactions }  from './views/transactions.js';
import { renderBillScanner }   from './views/bill-scanner.js';
import { renderSplit }         from './views/split.js';
import { renderAnalytics }     from './views/analytics.js';
import { renderProfile }       from './views/profile.js';
import { renderSubscriptions } from './views/subscriptions.js';
import { renderGoals }         from './views/goals.js';

const NAV_ITEMS = [
  { id: 'dashboard',     icon: 'dashboard',     label: 'Dashboard',        path: '/dashboard' },
  { id: 'transactions',  icon: 'transactions',  label: 'Transactions',     path: '/transactions' },
  { id: 'bill-scanner',  icon: 'bill-scanner',  label: 'Bill scanner',     path: '/bill-scanner' },
  { id: 'split',         icon: 'split',         label: 'Split expenses',   path: '/split' },
  { id: 'analytics',     icon: 'analytics',     label: 'Analytics',        path: '/analytics' },
  { id: 'subscriptions', icon: 'subscriptions', label: 'Subscriptions',    path: '/subscriptions' },
  { id: 'goals',         icon: 'goals',         label: 'Goals and budgets', path: '/goals' },
  { id: 'profile',       icon: 'profile',       label: 'Profile',          path: '/profile' },
];

const VIEW_BY_PATH = Object.fromEntries(NAV_ITEMS.map(n => [n.path, n.id]));
const PATH_BY_VIEW = Object.fromEntries(NAV_ITEMS.map(n => [n.id, n.path]));

// Views that live behind the bottom bar's "More" sheet.
const MORE_VIEWS = NAV_ITEMS
  .map(n => n.id)
  .filter(id => !['dashboard', 'transactions', 'split'].includes(id));

let currentView = viewFromPath();

function viewFromPath() {
  const path = location.pathname.replace(/\/+$/, '') || '/';
  return VIEW_BY_PATH[path] || 'dashboard';
}

function renderShell() {
  const root = document.getElementById('app');

  if (!Auth.isLoggedIn()) {
    SseManager.disconnect();
    root.innerHTML = '<div id="view-root"></div>';
    renderAuth(document.getElementById('view-root'));
    return;
  }

  SseManager.connect();
  applyTheme();

  currentView = viewFromPath();

  const email = Auth.getEmail() || 'User';
  let savedName = Auth.getName();
  if (savedName === 'undefined') savedName = '';
  const name = savedName || email.split('@')[0];
  const initial = avatar(name, 32);

  // Sidebar collapse is a desktop preference; remembered across reloads.
  let sidebarCollapsed = false;
  try { sidebarCollapsed = localStorage.getItem('pfa_sidebar_collapsed') === '1'; } catch { /* storage unavailable */ }

  root.innerHTML = `
    <div id="app-shell" class="${sidebarCollapsed ? 'sidebar-collapsed' : ''}">
      <aside class="sidebar" id="sidebar">
        <div class="sidebar-head">
          <a class="logo" href="/dashboard" data-view="dashboard" aria-label="PFA — Personal Finance Assistant" title="PFA — Personal Finance Assistant">
            <span class="logo-mark" aria-hidden="true">P</span>
            <span class="logo-text">PFA</span>
          </a>
          <button type="button" class="sidebar-collapse" id="sidebar-collapse" aria-label="Collapse navigation" title="Collapse navigation">${icon('chevron-left', 'sm')}</button>
        </div>
        <nav id="sidebar-nav" aria-label="Main navigation">
          ${NAV_ITEMS.map(n => `
            <a href="${n.path}" data-view="${n.id}" class="${n.id === currentView ? 'active' : ''}"
               aria-current="${n.id === currentView ? 'page' : 'false'}" title="${esc(n.label)}">
              <span class="icon">${icon(n.icon, 'sm')}</span><span class="nav-label">${esc(n.label)}</span>
            </a>`).join('')}
        </nav>
        <button type="button" class="sidebar-quick-add" id="sidebar-add-txn" ${currentView === 'transactions' ? 'hidden' : ''}>
          ${icon('plus', 'sm')}<span class="nav-label">Add transaction</span><kbd class="kbd">N</kbd>
        </button>
        <div class="sidebar-user-wrap">
          <button type="button" class="sidebar-user" id="sidebar-user" aria-haspopup="menu" aria-expanded="false" aria-label="Open account menu">
            <span id="shell-avatar">${initial}</span>
            <span class="sidebar-user-info">
              <span class="sidebar-user-name">${esc(name)}</span>
              <span class="sidebar-user-email">${esc(email)}</span>
            </span>
          </button>
          <div class="user-menu" id="user-menu" role="menu" hidden>
            <a class="user-menu-item" role="menuitem" href="/profile" data-view="profile">${icon('profile', 'sm')} Profile</a>
            <div class="user-menu-theme">
              <span class="user-menu-label">Theme</span>
              <div class="segmented segmented--sm" role="group" aria-label="Theme">
                <button type="button" class="segmented-item" data-theme-pref="system" role="menuitemradio">System</button>
                <button type="button" class="segmented-item" data-theme-pref="dark" role="menuitemradio">Dark</button>
                <button type="button" class="segmented-item" data-theme-pref="light" role="menuitemradio">Light</button>
              </div>
            </div>
            <button type="button" class="user-menu-item" role="menuitem" id="user-menu-signout">${icon('log-out', 'sm')} Sign out</button>
          </div>
        </div>
      </aside>
      <div class="main-content">
        <button type="button" class="sidebar-expand" id="sidebar-expand" aria-label="Expand navigation" title="Expand navigation">${icon('menu')}</button>
        <div id="view-root"></div>
      </div>

      <!-- Mobile bottom tab bar -->
      <nav class="bottom-nav" id="bottom-nav" aria-label="Main navigation">
        <a href="/dashboard" data-view="dashboard" class="bottom-nav-item ${currentView === 'dashboard' ? 'active' : ''}">
          ${icon('dashboard')}<span>Dashboard</span>
        </a>
        <a href="/transactions" data-view="transactions" class="bottom-nav-item ${currentView === 'transactions' ? 'active' : ''}">
          ${icon('transactions')}<span>Transactions</span>
        </a>
        <button type="button" id="mobile-fab" class="bottom-nav-add" aria-label="Add transaction">
          ${icon('plus')}
        </button>
        <a href="/split" data-view="split" class="bottom-nav-item ${currentView === 'split' ? 'active' : ''}">
          ${icon('split')}<span>Split</span>
        </a>
        <button type="button" id="mobile-more-btn" class="bottom-nav-item ${MORE_VIEWS.includes(currentView) ? 'active' : ''}" aria-label="More">
          ${icon('menu')}<span>More</span>
        </button>
      </nav>

      <!-- Mobile More sheet -->
      <div class="bottom-sheet-overlay" id="more-menu-overlay"></div>
      <div class="bottom-sheet" id="more-menu-sheet" role="menu" aria-label="More destinations" hidden>
        <div class="bottom-sheet-handle" aria-hidden="true"></div>
        <div class="more-menu-list">
          ${NAV_ITEMS.filter(n => MORE_VIEWS.includes(n.id)).map(n => `
            <a href="${n.path}" data-view="${n.id}" class="more-menu-item" role="menuitem">${icon(n.icon)} ${esc(n.label)}</a>`).join('')}
        </div>
      </div>
    </div>`;

  wireShell();
  updateNavActive(currentView);
  renderView();

  api.get('/auth/me').then(me => {
    Auth.setDefaultCurrency(me.defaultCurrency);
    if (me.profilePicture) {
      const sidebarAvatar = document.getElementById('shell-avatar');
      if (sidebarAvatar) {
        sidebarAvatar.innerHTML = avatar(me.name || name, 32, me.profilePicture);
      }
    }
  }).catch(() => {});
}

function wireShell() {
  document.querySelectorAll('#sidebar-nav a, .logo[data-view]').forEach(a => {
    a.onclick = (e) => { e.preventDefault(); navigateTo(a.dataset.view); };
  });

  document.querySelectorAll('#bottom-nav .bottom-nav-item[data-view]').forEach(a => {
    a.onclick = (e) => { e.preventDefault(); navigateTo(a.dataset.view); };
  });
  document.querySelectorAll('.more-menu-item[data-view], .user-menu-item[data-view]').forEach(a => {
    a.onclick = (e) => {
      e.preventDefault();
      closeBottomSheets();
      closeUserMenu();
      navigateTo(a.dataset.view);
    };
  });

  document.getElementById('sidebar-add-txn').onclick = openAddTransaction;
  document.getElementById('mobile-fab').onclick = openAddTransaction;

  const shell = document.getElementById('app-shell');
  const setSidebarCollapsed = (collapsed) => {
    shell.classList.toggle('sidebar-collapsed', collapsed);
    try { localStorage.setItem('pfa_sidebar_collapsed', collapsed ? '1' : '0'); } catch { /* storage unavailable */ }
  };
  document.getElementById('sidebar-collapse')?.addEventListener('click', () => setSidebarCollapsed(true));
  document.getElementById('sidebar-expand')?.addEventListener('click', () => setSidebarCollapsed(false));

  wireUserMenu();

  const moreOverlay = document.getElementById('more-menu-overlay');
  const moreSheet = document.getElementById('more-menu-sheet');
  const closeMore = () => {
    moreOverlay.classList.remove('open');
    moreSheet.classList.remove('open');
    moreSheet.hidden = true;
  };
  document.getElementById('mobile-more-btn').onclick = () => {
    const open = !moreSheet.classList.contains('open');
    moreOverlay.classList.toggle('open', open);
    moreSheet.classList.toggle('open', open);
    moreSheet.hidden = !open;
  };
  moreOverlay.onclick = closeMore;
}

function wireUserMenu() {
  const btn = document.getElementById('sidebar-user');
  const menu = document.getElementById('user-menu');
  if (!btn || !menu) return;

  const themeItems = [...menu.querySelectorAll('[data-theme-pref]')];
  const syncTheme = () => {
    const pref = getThemePreference();
    themeItems.forEach(b => {
      const active = b.dataset.themePref === pref;
      b.classList.toggle('is-active', active);
      b.setAttribute('aria-checked', active ? 'true' : 'false');
    });
  };
  themeItems.forEach(b => {
    b.onclick = () => {
      setThemePreference(b.dataset.themePref);
      syncTheme();
    };
  });
  syncTheme();

  btn.onclick = (e) => {
    e.stopPropagation();
    const open = menu.hidden;
    menu.hidden = !open;
    btn.setAttribute('aria-expanded', String(open));
    if (open) menu.querySelector('.user-menu-item')?.focus();
  };
  menu.onclick = (e) => e.stopPropagation();
  document.addEventListener('click', () => {
    if (!menu.hidden) { menu.hidden = true; btn.setAttribute('aria-expanded', 'false'); }
  });

  document.getElementById('user-menu-signout').onclick = async () => {
    closeUserMenu();
    try {
      const refresh = Auth.getRefresh();
      if (refresh) await api.post('/auth/logout', { refreshToken: refresh });
    } catch { /* ignore */ }
    Auth.clear();
    toast('Signed out', 'info');
    window.dispatchEvent(new Event('auth-change'));
  };
}

function closeUserMenu() {
  const menu = document.getElementById('user-menu');
  const btn = document.getElementById('sidebar-user');
  if (menu) menu.hidden = true;
  if (btn) btn.setAttribute('aria-expanded', 'false');
}

function closeBottomSheets() {
  const overlay = document.getElementById('more-menu-overlay');
  const sheet = document.getElementById('more-menu-sheet');
  if (overlay) overlay.classList.remove('open');
  if (sheet) { sheet.classList.remove('open'); sheet.hidden = true; }
}

/** Navigate to a top-level view and reflect it in the URL. */
export function navigateTo(view, { replace = false } = {}) {
  if (!PATH_BY_VIEW[view]) view = 'dashboard';
  currentView = view;

  const path = PATH_BY_VIEW[view];
  if (location.pathname !== path) {
    if (replace) history.replaceState({ view }, '', path);
    else history.pushState({ view }, '', path);
  }

  if (!document.getElementById('app-shell')) { renderShell(); return; }
  updateNavActive(view);
  renderView();
  try { window.scrollTo(0, 0); } catch { /* jsdom has no layout */ }
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

  const moreBtn = document.getElementById('mobile-more-btn');
  if (moreBtn) {
    const active = MORE_VIEWS.includes(view);
    moreBtn.classList.toggle('active', active);
    if (active) moreBtn.setAttribute('aria-current', 'page');
    else moreBtn.removeAttribute('aria-current');
  }

  const quickAdd = document.getElementById('sidebar-add-txn');
  if (quickAdd) quickAdd.hidden = view === 'transactions';
}

function openAddTransaction() {
  if (currentView !== 'transactions') navigateTo('transactions');
  // renderTransactions paints its header synchronously, so the button exists
  // on the next tick.
  setTimeout(() => {
    const addBtn = document.getElementById('t-add');
    if (addBtn) addBtn.click();
  }, 60);
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

/* ── Keyboard shortcuts ─────────────────────────────────────────────────── */
let gPressed = 0;

function isTypingTarget(el) {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

document.addEventListener('keydown', (e) => {
  if (!Auth.isLoggedIn() || e.metaKey || e.ctrlKey || e.altKey) return;
  const typing = isTypingTarget(e.target);
  const key = e.key;

  if (!typing && (key === 'n' || key === 'N')) {
    e.preventDefault();
    openAddTransaction();
    return;
  }

  if (!typing && key === '/') {
    const search = document.getElementById('t-search') || document.querySelector('.page-search input');
    if (search) { e.preventDefault(); search.focus(); }
    return;
  }

  if (!typing && key === '?') {
    e.preventDefault();
    openShortcutsDialog();
    return;
  }

  if (typing) return;

  if (key === 'g' || key === 'G') { gPressed = Date.now(); return; }
  if (Date.now() - gPressed < 1500) {
    const map = { d: 'dashboard', t: 'transactions', a: 'analytics', s: 'split', b: 'bill-scanner', p: 'profile' };
    const dest = map[key.toLowerCase()];
    if (dest) { e.preventDefault(); gPressed = 0; navigateTo(dest); }
  }
});

function openShortcutsDialog() {
  const rows = [
    ['N', 'Add transaction'],
    ['/', 'Focus search'],
    ['G then D', 'Dashboard'],
    ['G then T', 'Transactions'],
    ['G then A', 'Analytics'],
    ['G then S', 'Split expenses'],
    ['?', 'Show this dialog'],
  ];
  openModal('Keyboard shortcuts', `
    <div class="modal-detail-panel">
      ${rows.map(([keys, label]) => `
        <div class="modal-detail-row">
          <span class="detail-label">${esc(label)}</span>
          <span class="detail-value"><kbd class="kbd">${esc(keys)}</kbd></span>
        </div>`).join('')}
    </div>`);
}

window.navigateTo = navigateTo;
window.openAddTransaction = openAddTransaction;

window.addEventListener('popstate', () => {
  if (!Auth.isLoggedIn()) return;
  currentView = viewFromPath();
  updateNavActive(currentView);
  renderView();
});
window.addEventListener('auth-change', () => {
  currentView = viewFromPath();
  renderShell();
});
window.addEventListener('auth-expired', () => renderShell());

document.addEventListener('DOMContentLoaded', () => {
  applyTheme();
  renderShell();
  if (Auth.isLoggedIn() && !VIEW_BY_PATH[location.pathname.replace(/\/+$/, '') || '/']) {
    history.replaceState({ view: 'dashboard' }, '', '/dashboard');
  }
});
