import { api, Auth, toast } from '../utils/api.js';
import { icon } from '../utils/icons.js';
import {
  esc, pageHeader, openModal, currencyOptionHtml, formatMoney,
  getThemePreference, setThemePreference, avatar,
} from '../utils/ui.js';

export async function renderProfile(container) {
  const email = Auth.getEmail() || '';

  let profilePicture = '';
  let currentUsername = '';
  let defaultCurrency = Auth.getDefaultCurrency();
  try {
    const me = await api.get('/auth/me');
    profilePicture = me.profilePicture || '';
    currentUsername = me.username || me.name || '';
    defaultCurrency = me.defaultCurrency || defaultCurrency;
    Auth.setDefaultCurrency(defaultCurrency);
  } catch { /* render what we have */ }

  const displayName = currentUsername || email.split('@')[0] || 'User';

  container.innerHTML = `
    ${pageHeader('Profile', '')}
    <div class="profile-layout">
      <section class="profile-section" aria-labelledby="p-account-heading">
        <div class="profile-section-head">
          <h2 id="p-account-heading">Account</h2>
          <p>How you appear in groups and shared expenses.</p>
        </div>
        <div class="profile-identity">
          <button type="button" class="profile-avatar-lg" id="p-avatar"
            ${profilePicture ? `style="background-image:url('${esc(profilePicture)}')"` : ''}
            aria-label="Change profile picture" title="Change profile picture">
            ${profilePicture ? '' : avatar(displayName, 64)}
          </button>
          <div>
            <div class="profile-name">${esc(displayName)}</div>
            <div class="profile-email">${icon('mail', 'xs')} ${esc(email || '—')}</div>
          </div>
          <input type="file" id="p-avatar-upload" accept="image/*" hidden>
        </div>
        <form id="p-name-form">
          <div class="profile-field">
            <label for="p-username">Display name</label>
            <div class="profile-inline-form">
              <input class="form-input" id="p-username" type="text" value="${esc(currentUsername)}" placeholder="e.g. Nandini Chandra" required minlength="3" maxlength="50">
              <button type="submit" class="btn btn-primary" id="p-name-save" hidden>Save changes</button>
            </div>
            <p class="profile-help">Use the name people will recognise in split expenses.</p>
          </div>
        </form>
      </section>

      <section class="profile-section" aria-labelledby="p-prefs-heading">
        <div class="profile-section-head">
          <h2 id="p-prefs-heading">Preferences</h2>
          <p>Default currency, theme and number format.</p>
        </div>
        <div class="profile-field">
          <label for="p-currency">Default currency</label>
          <select class="form-select" id="p-currency">${currencyOptionHtml(defaultCurrency)}</select>
          <p class="profile-help">Receipts and reports are converted to this currency.</p>
        </div>
        <div class="profile-field">
          <span class="profile-field-label">Theme</span>
          <div class="segmented" id="p-theme" role="group" aria-label="Theme">
            <button type="button" class="segmented-item" data-theme-pref="system">System</button>
            <button type="button" class="segmented-item" data-theme-pref="dark">Dark</button>
            <button type="button" class="segmented-item" data-theme-pref="light">Light</button>
          </div>
        </div>
        <div class="profile-field">
          <span class="profile-field-label">Number format</span>
          <div class="profile-format-preview">${formatMoney(123456, defaultCurrency)}</div>
          <p class="profile-help">Indian grouping, a true minus sign and tabular figures.</p>
        </div>
      </section>

      <section class="profile-section" aria-labelledby="p-security-heading">
        <div class="profile-section-head">
          <h2 id="p-security-heading">Security</h2>
          <p>Keep your account protected.</p>
        </div>
        <button class="btn btn-secondary" id="p-change-password">Change password</button>
      </section>

      <section class="profile-section" aria-labelledby="p-session-heading">
        <div class="profile-section-head">
          <h2 id="p-session-heading">Session</h2>
          <p>Sign out from this device. Your data stays saved.</p>
        </div>
        <button class="btn btn-ghost" id="p-logout">${icon('log-out', 'sm')} Sign out</button>
      </section>
    </div>`;

  const nameInput = document.getElementById('p-username');
  const nameSave = document.getElementById('p-name-save');
  nameInput.oninput = () => {
    nameSave.hidden = nameInput.value.trim() === (currentUsername || '').trim();
  };

  document.getElementById('p-name-form').onsubmit = async (e) => {
    e.preventDefault();
    const value = nameInput.value.trim();
    try {
      await api.put('/auth/profile', { username: value });
      currentUsername = value;
      localStorage.setItem('pfa_name', value);
      nameSave.hidden = true;
      const sidebarName = document.querySelector('.sidebar-user-name');
      if (sidebarName) sidebarName.textContent = value;
      toast('Saved', 'success');
    } catch (err) { toast(err.message, 'error'); }
  };

  const currencySelect = document.getElementById('p-currency');
  if (window.TomSelect && !currencySelect.tomselect) {
    new TomSelect(currencySelect, { create: false, maxOptions: 200 });
  }
  currencySelect.addEventListener('change', async () => {
    const currency = currencySelect.value;
    try {
      await api.put('/auth/profile', { defaultCurrency: currency });
      Auth.setDefaultCurrency(currency);
      const preview = document.querySelector('.profile-format-preview');
      if (preview) preview.textContent = formatMoney(123456, currency);
      toast('Saved', 'success');
    } catch (err) { toast(err.message, 'error'); }
  });

  const themeWrap = document.getElementById('p-theme');
  const syncTheme = () => {
    const pref = getThemePreference();
    themeWrap.querySelectorAll('[data-theme-pref]').forEach(btn => {
      const active = btn.dataset.themePref === pref;
      btn.classList.toggle('is-active', active);
      btn.setAttribute('aria-pressed', String(active));
    });
  };
  themeWrap.querySelectorAll('[data-theme-pref]').forEach(btn => {
    btn.onclick = () => {
      setThemePreference(btn.dataset.themePref);
      syncTheme();
      toast('Theme updated', 'success');
    };
  });
  syncTheme();

  document.getElementById('p-change-password').onclick = () => {
    openModal('Change password', `
      <form id="pw-form">
        <div class="form-group"><label for="pw-current">Current password</label>
          <input class="form-input" id="pw-current" type="password" required autocomplete="current-password">
        </div>
        <div class="form-group"><label for="pw-new">New password</label>
          <input class="form-input" id="pw-new" type="password" required minlength="8" autocomplete="new-password">
          <p class="pw-strength" id="pw-strength">At least 8 characters.</p>
        </div>
        <div class="form-group"><label for="pw-confirm">Confirm new password</label>
          <input class="form-input" id="pw-confirm" type="password" required minlength="8" autocomplete="new-password">
        </div>
        <div class="modal-actions">
          <button type="button" class="btn btn-secondary" data-modal-cancel>Cancel</button>
          <button type="submit" class="btn btn-primary">Save changes</button>
        </div>
      </form>`, {
      onSubmit: async () => {
        const current = document.getElementById('pw-current').value;
        const next = document.getElementById('pw-new').value;
        const confirm = document.getElementById('pw-confirm').value;
        if (next !== confirm) throw new Error('The new passwords do not match');
        await api.post('/auth/change-password', { currentPassword: current, newPassword: next });
        toast('Password changed', 'success');
      }
    });

    const pwNew = document.getElementById('pw-new');
    const hint = document.getElementById('pw-strength');
    pwNew.oninput = () => {
      const v = pwNew.value;
      let level = 'weak';
      let text = 'Use at least 8 characters.';
      if (v.length >= 8 && /[A-Z]/.test(v) && /[0-9]/.test(v)) { level = 'strong'; text = 'Strong password.'; }
      else if (v.length >= 8) { level = 'ok'; text = 'Add a capital letter and a number to strengthen it.'; }
      hint.dataset.level = level;
      hint.textContent = text;
    };
  };

  document.getElementById('p-logout').onclick = async () => {
    try {
      const refresh = Auth.getRefresh();
      if (refresh) await api.post('/auth/logout', { refreshToken: refresh });
    } catch { /* sign out locally regardless */ }
    Auth.clear();
    toast('Signed out', 'info');
    window.dispatchEvent(new Event('auth-change'));
  };

  /* Profile picture */
  const avatarBtn = document.getElementById('p-avatar');
  const uploadInput = document.getElementById('p-avatar-upload');
  avatarBtn.onclick = () => uploadInput.click();

  uploadInput.onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = (event) => {
      const { overlay, close } = openModal('Crop profile picture', `
        <div style="max-height:60vh;display:flex;justify-content:center;align-items:center;background:var(--bg);border-radius:8px;overflow:hidden">
          <img id="crop-image" src="${event.target.result}" alt="Picture to crop" style="max-width:100%;max-height:60vh;display:block">
        </div>
        <div class="modal-actions">
          <button class="btn btn-ghost" type="button" data-modal-cancel>Cancel</button>
          <button class="btn btn-primary" id="crop-save-btn" type="button">Save picture</button>
        </div>
      `);

      const imgEl = overlay.querySelector('#crop-image');
      const saveBtn = overlay.querySelector('#crop-save-btn');
      let cropper = null;
      if (window.Cropper) {
        cropper = new Cropper(imgEl, { aspectRatio: 1, viewMode: 1, autoCropArea: 0.8, background: false });
      }

      saveBtn.onclick = async () => {
        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving…';
        const canvas = cropper ? cropper.getCroppedCanvas({ width: 250, height: 250 }) : null;
        const blob = canvas ? await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85)) : file;
        if (!blob) {
          toast('Could not process that image', 'error');
          saveBtn.disabled = false;
          saveBtn.textContent = 'Save picture';
          return;
        }

        try {
          const formData = new FormData();
          formData.append('file', blob, 'profile.jpg');
          const uploadRes = await fetch('/api/profile/upload', {
            method: 'POST',
            body: formData,
            headers: { Authorization: 'Bearer ' + Auth.getToken() },
          });
          if (!uploadRes.ok) throw new Error('Upload failed: ' + uploadRes.status);
          const { url: imageUrl } = await uploadRes.json();

          await api.put('/auth/profile', { profilePicture: imageUrl });
          profilePicture = imageUrl;
          avatarBtn.style.backgroundImage = `url('${imageUrl}')`;
          avatarBtn.innerHTML = '';
          const sidebarAvatar = document.getElementById('shell-avatar');
          if (sidebarAvatar) sidebarAvatar.innerHTML = avatar(displayName, 32, imageUrl);
          toast('Picture updated', 'success');
          close();
        } catch (err) {
          toast('Could not upload the picture: ' + err.message, 'error');
          saveBtn.disabled = false;
          saveBtn.textContent = 'Save picture';
        }
      };
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };
}
