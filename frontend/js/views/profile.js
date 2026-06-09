import { api, Auth, toast } from '../utils/api.js';
import { esc, pageHeader } from '../utils/ui.js';

export async function renderProfile(container) {
  const email = Auth.getEmail();
  const userId = Auth.getUserId();
  container.innerHTML = `
    ${pageHeader('Profile', 'Manage your account and security settings')}
    <div class="profile-grid fade-up">
      <div class="card">
        <div class="card-header"><h3>Account Info</h3></div>
        <div style="display:flex;align-items:center;gap:16px">
          <div class="profile-avatar-lg">${(email || 'U')[0].toUpperCase()}</div>
          <div>
            <div style="font-weight:600;font-size:1.05rem">${esc(email || 'User')}</div>
            <div style="color:var(--text-dim);font-size:.82rem;margin-top:2px">ID: ${esc(userId?.substring(0, 16) || '—')}…</div>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-header"><h3>Change Password</h3></div>
        <form id="pw-form">
          <div class="form-group"><label for="pw-current">Current Password</label>
            <input class="form-input" id="pw-current" type="password" required minlength="8" autocomplete="current-password"></div>
          <div class="form-group"><label for="pw-new">New Password</label>
            <input class="form-input" id="pw-new" type="password" required minlength="8" autocomplete="new-password"></div>
          <button type="submit" class="btn btn-primary" style="width:100%">Update Password</button>
        </form>
      </div>
      <div class="card">
        <div class="card-header"><h3>Session</h3></div>
        <p style="color:var(--text-dim);font-size:.88rem;margin-bottom:16px">Sign out from this device. Your data remains saved.</p>
        <button class="btn btn-danger" id="p-logout" style="width:100%">🚪 Logout</button>
      </div>
    </div>`;

  document.getElementById('pw-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api.post('/auth/change-password', {
        currentPassword: document.getElementById('pw-current').value,
        newPassword: document.getElementById('pw-new').value
      });
      toast('Password updated', 'success');
      document.getElementById('pw-form').reset();
    } catch (err) { toast(err.message, 'error'); }
  };

  document.getElementById('p-logout').onclick = async () => {
    try {
      const refresh = Auth.getRefresh();
      if (refresh) await api.post('/auth/logout', { refreshToken: refresh });
    } catch { /* ignore */ }
    Auth.clear();
    toast('Logged out', 'info');
    window.dispatchEvent(new Event('auth-change'));
  };
}
