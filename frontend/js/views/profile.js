import { api, Auth, toast } from '../utils/api.js';

export async function renderProfile(container) {
  const email = Auth.getEmail();
  container.innerHTML = `
    <div class="page-header fade-up"><h1>Profile</h1><p>Account settings</p></div>
    <div style="max-width:480px">
      <div class="card fade-up" style="animation-delay:.05s;margin-bottom:20px">
        <h3 style="margin-bottom:16px;font-weight:600">Account Info</h3>
        <div style="display:flex;align-items:center;gap:16px;margin-bottom:16px">
          <div style="width:56px;height:56px;border-radius:50%;background:var(--gradient);display:flex;align-items:center;justify-content:center;font-size:1.5rem;font-weight:700">${(email||'U')[0].toUpperCase()}</div>
          <div><div style="font-weight:600;font-size:1.05rem">${email || 'User'}</div><div style="color:var(--text-dim);font-size:.82rem">User ID: ${Auth.getUserId()?.substring(0,12) || '—'}…</div></div>
        </div>
      </div>
      <div class="card fade-up" style="animation-delay:.1s;margin-bottom:20px">
        <h3 style="margin-bottom:16px;font-weight:600">Change Password</h3>
        <form id="pw-form">
          <div class="form-group"><label>Current Password</label><input class="form-input" id="pw-current" type="password" required minlength="8"></div>
          <div class="form-group"><label>New Password</label><input class="form-input" id="pw-new" type="password" required minlength="8"></div>
          <button type="submit" class="btn btn-primary" style="width:100%;justify-content:center">Update Password</button>
        </form>
      </div>
      <div class="card fade-up" style="animation-delay:.15s">
        <h3 style="margin-bottom:16px;font-weight:600">Session</h3>
        <button class="btn btn-danger" id="p-logout" style="width:100%;justify-content:center">🚪 Logout</button>
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
    } catch {}
    Auth.clear();
    toast('Logged out', 'info');
    window.dispatchEvent(new Event('auth-change'));
  };
}
