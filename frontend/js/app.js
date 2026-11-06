/* ═══════════════════════════════════════════════════════════════════════════
   app.js — Router + application shell
   ═══════════════════════════════════════════════════════════════════════════ */
import { Auth, SseManager, api, toast, requestBrowserNotificationPermission } from './utils/api.js';
import { icon } from './utils/icons.js';
import { renderAuth }          from './views/auth.js';
import {
  esc, avatar, applyTheme, getThemePreference, setThemePreference,
  openModal, displayName,
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

/* ── Notification inbox ─────────────────────────────────────────────────── */
const NOTIF = { items: [], unread: 0, announced: false };

async function refreshNotifications() {
  if (!Auth.isLoggedIn()) return;
  try {
    const [page, count] = await Promise.all([
      api.get('/upsert/notifications', { page: 0, size: 25 }),
      api.get('/upsert/notifications/unread-count'),
    ]);
    NOTIF.items = page.items || [];
    NOTIF.unread = count.unread ?? page.unread ?? 0;
    updateNotifBadges();
    if (!NOTIF.announced && NOTIF.unread > 0) {
      NOTIF.announced = true;
      toast(`${NOTIF.unread} notification${NOTIF.unread === 1 ? '' : 's'} while you were away`,
        'info', { duration: 8000 });
    }
  } catch { /* the inbox is best-effort; never break the shell for it */ }
}

function updateNotifBadges() {
  document.querySelectorAll('[data-notif-badge]').forEach(b => {
    if (NOTIF.unread > 0) {
      b.textContent = NOTIF.unread > 99 ? '99+' : String(NOTIF.unread);
      b.hidden = false;
    } else {
      b.hidden = true;
    }
  });
}

function notifTime(value) {
  if (!value) return '';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '';
  const diff = Date.now() - d.getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

function openNotificationsPanel() {
  const rows = NOTIF.items.length ? NOTIF.items.map(n => `
    <button type="button" class="notif-item ${n.readAt ? '' : 'is-unread'}"
            data-notif-id="${n.id}" data-notif-group="${n.groupId ?? ''}">
      <span class="notif-dot ${n.status === 'ERROR' ? 'is-neg' : n.status === 'SUCCESS' ? 'is-pos' : ''}"></span>
      <span class="notif-body">
        <span class="notif-msg">${esc(n.message)}</span>
        <span class="notif-time">${esc(notifTime(n.createdAt))}</span>
      </span>
    </button>`).join('')
    : '<p class="meta" style="padding:16px 0">No notifications yet.</p>';

  openModal('Notifications', `
    <div class="notif-toolbar">
      <button type="button" class="btn btn-ghost btn-sm" id="notif-read-all">Mark all read</button>
      <button type="button" class="btn btn-ghost btn-sm" id="notif-permission">
        ${icon('bell', 'sm')} Enable browser notifications
      </button>
    </div>
    <div class="notif-list">${rows}</div>`);

  setTimeout(() => {
    document.getElementById('notif-read-all')?.addEventListener('click', async () => {
      try { await api.post('/upsert/notifications/read-all', null); } catch { /* ignore */ }
      NOTIF.items.forEach(n => { n.readAt = n.readAt || new Date().toISOString(); });
      NOTIF.unread = 0;
      updateNotifBadges();
      document.querySelector('.modal-close')?.click();
      openNotificationsPanel();
    });

    document.getElementById('notif-permission')?.addEventListener('click', async () => {
      const state = await requestBrowserNotificationPermission();
      toast(state === 'granted' ? 'Browser notifications enabled' : 'Browser notifications not enabled',
        state === 'granted' ? 'success' : 'info');
    });

    document.querySelectorAll('[data-notif-id]').forEach(btn => {
      btn.onclick = async () => {
        const id = btn.dataset.notifId;
        try { await api.post(`/upsert/notifications/${id}/read`, null); } catch { /* ignore */ }
        const item = NOTIF.items.find(n => String(n.id) === String(id));
        if (item && !item.readAt) {
          item.readAt = new Date().toISOString();
          NOTIF.unread = Math.max(0, NOTIF.unread - 1);
          updateNotifBadges();
        }
        const groupId = btn.dataset.notifGroup;
        document.querySelector('.modal-close')?.click();
        if (groupId) {
          window._pfaOpenGroupId = groupId;
          navigateTo('split');
        } else {
          openNotificationsPanel();
        }
      };
    });
  }, 20);
}

window.addEventListener('app-notification', (e) => {
  if (!Auth.isLoggedIn()) return;
  const id = e.detail?.notificationId;
  // Seen live → read. Arrived while the tab was in the background → keep it
  // unread so the inbox badge is honest when the user comes back.
  if (document.visibilityState === 'visible' && id != null) {
    api.post(`/upsert/notifications/${id}/read`, null)
      .catch(() => { /* the next badge refresh recovers */ })
      .finally(refreshNotifications);
  } else {
    refreshNotifications();
  }
});

function viewFromPath() {
  const path = location.pathname.replace(/\/+$/, '') || '/';
  return VIEW_BY_PATH[path] || 'dashboard';
}

function renderShell() {
  const root = document.getElementById('app');

  if (!Auth.isLoggedIn()) {
    SseManager.disconnect();
    NOTIF.items = [];
    NOTIF.unread = 0;
    NOTIF.announced = false;
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
  const name = displayName(savedName || email.split('@')[0], 'User');
  const initial = avatar(name, 32);

  // Sidebar collapse is a desktop preference; remembered across reloads.
  let sidebarCollapsed = false;
  try { sidebarCollapsed = localStorage.getItem('pfa_sidebar_collapsed') === '1'; } catch { /* storage unavailable */ }

  root.innerHTML = `
    <div id="app-shell" class="${sidebarCollapsed ? 'sidebar-collapsed' : ''}">
      <aside class="sidebar" id="sidebar">
        <div class="sidebar-head">
          <a class="logo" href="/dashboard" data-view="dashboard" aria-label="PFA — Personal Finance Assistant" title="PFA — Personal Finance Assistant">
            <span class="logo-text">PFA</span>
          </a>
          <div class="sidebar-head-tools">
            <button type="button" class="sidebar-bell" id="sidebar-bell" aria-label="Notifications" title="Notifications">
              ${icon('bell', 'sm')}<span class="notif-badge" data-notif-badge hidden></span>
            </button>
            <button type="button" class="sidebar-collapse" id="sidebar-collapse" aria-label="Collapse navigation" title="Collapse navigation">${icon('chevron-left', 'sm')}</button>
          </div>
        </div>
        <nav id="sidebar-nav" aria-label="Main navigation">
          ${NAV_ITEMS.map(n => `
            <a href="${n.path}" data-view="${n.id}" class="${n.id === currentView ? 'active' : ''}"
               aria-current="${n.id === currentView ? 'page' : 'false'}" title="${esc(n.label)}">
              <span class="icon">${icon(n.icon, 'sm')}</span><span class="nav-label">${esc(n.label)}</span>
            </a>`).join('')}
        </nav>
        <div class="sidebar-user-wrap">
          <button type="button" class="sidebar-user" id="sidebar-user" aria-haspopup="menu" aria-expanded="false" aria-label="Open account menu">
            <span id="shell-avatar">${initial}</span>
            <span class="sidebar-user-info">
              <span class="sidebar-user-name" title="${esc(name)}">${esc(name)}</span>
              <span class="sidebar-user-email" title="${esc(email)}">${esc(email)}</span>
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
        <button type="button" class="mobile-notif" id="mobile-notif" aria-label="Notifications" title="Notifications">
          ${icon('bell', 'sm')}<span class="notif-badge" data-notif-badge hidden></span>
        </button>
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
      <div class="bottom-sheet" id="more-menu-sheet" role="dialog" aria-modal="true" aria-label="More destinations" hidden>
        <div class="bottom-sheet-handle" aria-hidden="true"></div>
        <div class="more-menu-list" role="menu">
          ${NAV_ITEMS.filter(n => MORE_VIEWS.includes(n.id)).map(n => `
            <a href="${n.path}" data-view="${n.id}" class="more-menu-item" role="menuitem">${icon(n.icon)} ${esc(n.label)}</a>`).join('')}
        </div>
      </div>
    </div>`;

  wireShell();
  updateNavActive(currentView);
  renderView();
  refreshNotifications();

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
  document.getElementById('sidebar-bell')?.addEventListener('click', openNotificationsPanel);
  document.getElementById('mobile-notif')?.addEventListener('click', openNotificationsPanel);

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
  const moreBtn = document.getElementById('mobile-more-btn');
  const sheetItems = () => [...moreSheet.querySelectorAll('.more-menu-item')];

  const closeMore = () => {
    moreOverlay.classList.remove('open');
    moreSheet.classList.remove('open');
    moreSheet.hidden = true;
    document.body.classList.remove('sheet-open');
  };
  const openMore = () => {
    moreOverlay.classList.add('open');
    moreSheet.classList.add('open');
    moreSheet.hidden = false;
    document.body.classList.add('sheet-open');
    sheetItems()[0]?.focus();
  };
  moreBtn.onclick = () => {
    if (moreSheet.classList.contains('open')) closeMore();
    else openMore();
  };
  moreOverlay.onclick = closeMore;

  // Trap focus in the sheet and let Escape dismiss it.
  moreSheet.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      closeMore();
      moreBtn.focus();
      return;
    }
    if (e.key !== 'Tab') return;
    const items = sheetItems();
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  });
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
  document.body.classList.remove('sheet-open');
}

/** Navigate to a top-level view and reflect it in the URL. */
export function navigateTo(view, { replace = false, query = '' } = {}) {
  if (!PATH_BY_VIEW[view]) view = 'dashboard';
  currentView = view;

  const path = PATH_BY_VIEW[view] + (query || '');
  if (`${location.pathname}${location.search}` !== path) {
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
