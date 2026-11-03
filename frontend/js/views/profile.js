import { api, Auth, toast } from '../utils/api.js?v=2026110308';
import { icon } from '../utils/icons.js?v=2026110308';
import { esc, pageHeader, openModal, currencyOptionHtml } from '../utils/ui.js?v=2026110308';
export async function renderProfile(container) {
  const email = Auth.getEmail();
  const userId = Auth.getUserId();
  
  let profilePicture = '';
  let currentUsername = '';
  let defaultCurrency = Auth.getDefaultCurrency();
  try {
    const me = await api.get('/auth/me');
    profilePicture = me.profilePicture || '';
    currentUsername = me.username || '';
    defaultCurrency = me.defaultCurrency || defaultCurrency;
    Auth.setDefaultCurrency(defaultCurrency);
  } catch (e) {
    console.error('Failed to load profile', e);
  }

  const displayName = currentUsername || (email ? email.split('@')[0] : 'User');
  const initial = (displayName || 'U')[0].toUpperCase();
  const shortId = userId ? userId.substring(0, 12) : '—';

  container.innerHTML = `
    ${pageHeader('Profile', 'Manage your account and security settings')}
    <div class="profile-layout fade-up">
      <div class="profile-main">
      <section class="profile-hero" aria-label="Account overview">
        <div class="profile-hero-aura" aria-hidden="true"></div>
        <div class="profile-hero-body">
          <div class="profile-avatar-wrap">
            <div class="profile-avatar-lg" id="p-avatar" style="cursor:pointer;position:relative;overflow:hidden;${profilePicture ? `background-image:url('${profilePicture}')` : ''}" title="Click to change profile picture" role="button" tabindex="0" aria-label="Change profile picture">
              ${profilePicture ? '' : initial}
              <div class="avatar-overlay" style="position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;opacity:0;transition:opacity 0.2s;color:#fff;pointer-events:none;">${icon('camera', 'sm')}</div>
            </div>
            <input type="file" id="p-avatar-upload" accept="image/*" hidden>
          </div>
          <div class="profile-identity">
            <span class="profile-eyebrow">${icon('sparkles', 'xs')} Personal account</span>
            <h2 class="profile-name">${esc(displayName)}</h2>
            <p class="profile-email">${icon('mail', 'xs')} ${esc(email || '—')}</p>
            <div class="profile-chips">
              <span class="profile-chip profile-chip--ok">${icon('shield', 'xs')} Secured</span>
              <span class="profile-chip">${icon('wallet', 'xs')} ${esc(defaultCurrency)}</span>
              <span class="profile-chip">${icon('at-sign', 'xs')} ID ${esc(shortId)}</span>
            </div>
          </div>
        </div>
        <div class="profile-divider"></div>
        <form id="p-username-form" class="profile-form">
          <div class="profile-field">
            <label for="p-username">Display name</label>
            <p class="profile-help">This is how you appear in groups and shared expenses.</p>
            <div class="profile-inline-form">
              <input class="form-input" id="p-username" type="text" value="${esc(currentUsername)}" placeholder="Choose a username" required minlength="3" maxlength="30" pattern="^[a-zA-Z0-9_]+$">
              <button type="submit" class="btn btn-primary">Save</button>
            </div>
          </div>
        </form>
      </section>

        <section class="profile-panel">
          <div class="profile-panel-head">
            <div class="profile-panel-icon profile-panel-icon--warn">${icon('shield', 'sm')}</div>
            <div>
              <h3>Session</h3>
              <p>Sign out from this device. Your data remains saved.</p>
            </div>
          </div>
          <button class="btn btn-danger profile-submit" id="p-logout">${icon('log-out', 'sm')} Sign out</button>
        </section>
      </div>

      <div class="profile-side">
        <section class="profile-panel">
          <div class="profile-panel-head">
            <div class="profile-panel-icon">${icon('wallet', 'sm')}</div>
            <div>
              <h3>Default currency</h3>
              <p>Receipts and reports are converted to this currency.</p>
            </div>
          </div>
          <form id="p-currency-form" class="profile-form">
            <div class="profile-field">
              <label for="p-currency">Currency</label>
              <div class="profile-inline-form">
                <select class="form-select" id="p-currency">${currencyOptionHtml(defaultCurrency)}</select>
                <button type="submit" class="btn btn-primary">Save</button>
              </div>
            </div>
          </form>
        </section>

        <section class="profile-panel">
          <div class="profile-panel-head">
            <div class="profile-panel-icon">${icon('lock', 'sm')}</div>
            <div>
              <h3>Change password</h3>
              <p>Use at least 8 characters. You'll stay signed in on this device.</p>
            </div>
          </div>
          <form id="pw-form" class="profile-form">
            <div class="profile-field">
              <label for="pw-current">Current password</label>
              <input class="form-input" id="pw-current" type="password" required minlength="8" autocomplete="current-password" placeholder="Enter current password">
            </div>
            <div class="profile-field">
              <label for="pw-new">New password</label>
              <input class="form-input" id="pw-new" type="password" required minlength="8" autocomplete="new-password" placeholder="At least 8 characters">
            </div>
            <button type="submit" class="btn btn-primary profile-submit">Update password</button>
          </form>
        </section>
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

  document.getElementById('p-currency-form').onsubmit = async (e) => {
    e.preventDefault();
    const currency = document.getElementById('p-currency').value;
    try {
      await api.put('/auth/profile', { defaultCurrency: currency });
      Auth.setDefaultCurrency(currency);
      toast(`Default currency set to ${currency}`, 'success');
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
  avatarDiv.onfocus = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '1';
  avatarDiv.onblur = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '0';
  avatarDiv.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); avatarDiv.click(); } };
  
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
        canvas.toBlob(async (blob) => {
          if (!blob) {
            toast('Failed to crop image', 'error');
            saveBtn.disabled = false;
            saveBtn.textContent = 'Save Picture';
            return;
          }

          const formData = new FormData();
          formData.append('file', blob, 'profile.jpg');

          try {
            // First, upload the image to MinIO
            const uploadRes = await fetch('/api/profile/upload', {
              method: 'POST',
              body: formData,
              headers: { 'Authorization': 'Bearer ' + Auth.getToken() }
            });
            if (!uploadRes.ok) throw new Error('Upload failed: ' + uploadRes.status);
            const { url: imageUrl } = await uploadRes.json();

            // Save the URL (not base64) to the database
            await api.put('/auth/profile', { profilePicture: imageUrl });
            profilePicture = imageUrl;
            avatarDiv.style.backgroundImage = `url('${imageUrl}')`;
            avatarDiv.innerHTML = `<div class="avatar-overlay" style="position:absolute;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;opacity:0;transition:opacity 0.2s;color:#fff;">${icon('camera', 'sm')}</div>`;
            avatarDiv.onmouseenter = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '1';
            avatarDiv.onmouseleave = () => avatarDiv.querySelector('.avatar-overlay').style.opacity = '0';

            toast('Profile picture updated', 'success');

            const sidebarAvatar = document.querySelector('.sidebar-user-avatar');
            if (sidebarAvatar) {
              sidebarAvatar.style.backgroundImage = `url('${imageUrl}')`;
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
        }, 'image/jpeg', 0.85);
      };
    };
    reader.readAsDataURL(file);
    e.target.value = ''; // allow picking the same file again
  };
}
