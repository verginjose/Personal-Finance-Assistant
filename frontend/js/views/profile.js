import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import { esc, pageHeader } from '../utils/ui.js';

export async function renderProfile(container) {
  const email = Auth.getEmail();
  const userId = Auth.getUserId();
  
  let profilePicture = '';
  try {
    const me = await api.get('/auth/me');
    profilePicture = me.profilePicture || '';
  } catch (e) {
    console.error('Failed to load profile', e);
  }

  container.innerHTML = `
    ${pageHeader('Profile', 'Manage your account and security settings')}
    <div class="profile-grid fade-up">
      <div class="card">
        <div class="card-header"><h3>Account Info</h3></div>
        <div style="display:flex;align-items:center;gap:16px">
          <div class="profile-avatar-lg" id="p-avatar" style="cursor:pointer;position:relative;overflow:hidden;background-size:cover;background-position:center;${profilePicture ? `background-image:url('${profilePicture}')` : ''}" title="Click to change profile picture">
            ${profilePicture ? '' : (email || 'U')[0].toUpperCase()}
            <div class="avatar-overlay" style="position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;opacity:0;transition:opacity 0.2s;color:#fff;">${icon('camera', 'sm')}</div>
          </div>
          <input type="file" id="p-avatar-upload" accept="image/*" hidden>
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
        <button class="btn btn-danger" id="p-logout" style="width:100%">${icon('log-out', 'sm')} Sign Out</button>
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

  const avatarDiv = document.getElementById('p-avatar');
  const uploadInput = document.getElementById('p-avatar-upload');
  
  avatarDiv.onmouseenter = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '1';
  avatarDiv.onmouseleave = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '0';
  avatarDiv.onclick = () => uploadInput.click();

  uploadInput.onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const img = new Image();
      img.onload = async () => {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        const size = 150;
        canvas.width = size;
        canvas.height = size;

        // Crop and center
        const scale = Math.max(size / img.width, size / img.height);
        const x = (size / scale - img.width) / 2;
        const y = (size / scale - img.height) / 2;
        
        ctx.drawImage(img, x, y, img.width, img.height, 0, 0, img.width * scale, img.height * scale);
        
        const dataUrl = canvas.toDataURL('image/jpeg', 0.8);
        
        try {
          await api.put('/auth/profile-picture', { profilePicture: dataUrl });
          avatarDiv.style.backgroundImage = `url('${dataUrl}')`;
          avatarDiv.innerHTML = `<div class="avatar-overlay" style="position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;opacity:0;transition:opacity 0.2s;color:#fff;">${icon('camera', 'sm')}</div>`;
          avatarDiv.onmouseenter = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '1';
          avatarDiv.onmouseleave = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '0';
          
          toast('Profile picture updated', 'success');
          
          // Try to update sidebar if possible
          const sidebarAvatar = document.querySelector('.sidebar-user-avatar');
          if (sidebarAvatar) {
            sidebarAvatar.style.backgroundImage = `url('${dataUrl}')`;
            sidebarAvatar.style.backgroundSize = 'cover';
            sidebarAvatar.style.backgroundPosition = 'center';
            sidebarAvatar.textContent = '';
          }
        } catch (err) {
          toast('Failed to upload picture: ' + err.message, 'error');
        }
      };
      img.src = event.target.result;
    };
    reader.readAsDataURL(file);
  };
}
