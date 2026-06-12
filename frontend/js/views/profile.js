import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import { esc, pageHeader, openModal } from '../utils/ui.js';
export async function renderProfile(container) {
  const email = Auth.getEmail();
  const userId = Auth.getUserId();
  
  let profilePicture = '';
  let currentUsername = '';
  try {
    const me = await api.get('/auth/me');
    profilePicture = me.profilePicture || '';
    currentUsername = me.username || '';
  } catch (e) {
    console.error('Failed to load profile', e);
  }

  container.innerHTML = `
    ${pageHeader('Profile', 'Manage your account and security settings')}
    <div class="profile-grid fade-up">
      <div class="card">
        <div class="card-header"><h3>Account Info</h3></div>
        <div style="display:flex;align-items:center;gap:16px;margin-bottom:16px;">
          <div class="profile-avatar-lg" id="p-avatar" style="cursor:pointer;position:relative;overflow:hidden;background-size:cover;background-position:center;${profilePicture ? `background-image:url('${profilePicture}')` : ''}" title="Click to change profile picture">
            ${profilePicture ? '' : (email || 'U')[0].toUpperCase()}
            <div class="avatar-overlay" style="position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;opacity:0;transition:opacity 0.2s;color:#fff;">${icon('camera', 'sm')}</div>
          </div>
          <input type="file" id="p-avatar-upload" accept="image/*" hidden>
          <div style="flex:1">
            <div style="font-weight:600;font-size:1.05rem">${esc(email || 'User')}</div>
            <div style="color:var(--text-dim);font-size:.82rem;margin-top:2px">ID: ${esc(userId?.substring(0, 16) || '—')}…</div>
          </div>
        </div>
        <form id="p-username-form" style="display:flex;gap:8px;align-items:flex-end;">
          <div class="form-group" style="flex:1;margin-bottom:0;"><label for="p-username">Username</label>
            <input class="form-input" id="p-username" type="text" value="${esc(currentUsername)}" required minlength="3" maxlength="30" pattern="^[a-zA-Z0-9_]+$"></div>
          <button type="submit" class="btn btn-primary">Save Username</button>
        </form>
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

  document.getElementById('p-username-form').onsubmit = async (e) => {
    e.preventDefault();
    try {
      await api.put('/auth/profile', {
        username: document.getElementById('p-username').value
      });
      toast('Username updated successfully', 'success');
    } catch (err) { toast(err.message, 'error'); }
  };

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
  
  avatarDiv.onclick = () => {
    const body = `
      <div style="display:flex;flex-direction:column;align-items:center;gap:20px;padding:10px;">
        ${profilePicture 
          ? `<img src="${profilePicture}" style="width:250px;height:250px;border-radius:50%;object-fit:cover;box-shadow:0 8px 24px rgba(0,0,0,0.2);" />` 
          : `<div style="width:250px;height:250px;border-radius:50%;background:var(--bg-elevated);display:flex;align-items:center;justify-content:center;font-size:5rem;color:var(--text-dim);box-shadow:0 8px 24px rgba(0,0,0,0.2);">${(email || 'U')[0].toUpperCase()}</div>`
        }
        <button class="btn btn-primary" id="view-upload-btn" type="button">${icon('camera', 'sm')} Update Picture</button>
      </div>
    `;
    const { overlay, close } = openModal('Profile Picture', body);
    overlay.querySelector('#view-upload-btn').onclick = () => {
      close();
      uploadInput.click();
    };
  };

  uploadInput.onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const modalBody = `
        <div style="max-height: 60vh; display: flex; justify-content: center; align-items: center; background: #000; border-radius: 8px; overflow: hidden;">
          <img id="crop-image" src="${event.target.result}" style="max-width: 100%; max-height: 60vh; display: block;" />
        </div>
        <div style="margin-top: 16px; display: flex; justify-content: flex-end; gap: 8px;">
          <button class="btn btn-ghost" type="button" data-modal-cancel>Cancel</button>
          <button class="btn btn-primary" id="crop-save-btn" type="button">Save Picture</button>
        </div>
      `;

      const { overlay, close } = openModal('Crop Profile Picture', modalBody);
      const imgEl = overlay.querySelector('#crop-image');
      const saveBtn = overlay.querySelector('#crop-save-btn');

      // Initialize Cropper
      const cropper = new Cropper(imgEl, {
        aspectRatio: 1,
        viewMode: 1,
        autoCropArea: 0.8,
        background: false,
      });

      saveBtn.onclick = async () => {
        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving...';
        
        const canvas = cropper.getCroppedCanvas({ width: 250, height: 250 });
        const dataUrl = canvas.toDataURL('image/jpeg', 0.85);

        try {
          await api.put('/auth/profile', { profilePicture: dataUrl });
          profilePicture = dataUrl;
          avatarDiv.style.backgroundImage = `url('${dataUrl}')`;
          avatarDiv.innerHTML = `<div class="avatar-overlay" style="position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;opacity:0;transition:opacity 0.2s;color:#fff;">${icon('camera', 'sm')}</div>`;
          avatarDiv.onmouseenter = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '1';
          avatarDiv.onmouseleave = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '0';
          
          toast('Profile picture updated', 'success');
          
          const sidebarAvatar = document.querySelector('.sidebar-user-avatar');
          if (sidebarAvatar) {
            sidebarAvatar.style.backgroundImage = `url('${dataUrl}')`;
            sidebarAvatar.style.backgroundSize = 'cover';
            sidebarAvatar.style.backgroundPosition = 'center';
            sidebarAvatar.textContent = '';
          }
          close();
        } catch (err) {
          toast('Failed to upload picture: ' + err.message, 'error');
          saveBtn.disabled = false;
          saveBtn.textContent = 'Save Picture';
        }
      };
    };
    reader.readAsDataURL(file);
    e.target.value = ''; // allow picking the same file again
  };
}
