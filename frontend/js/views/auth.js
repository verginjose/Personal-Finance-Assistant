import { api, Auth, toast } from '../utils/api.js?v=1781338888';
import { icon } from '../utils/icons.js?v=1781338888';

export function renderAuth(container) {
  let mode = 'login';

  function draw() {
    container.innerHTML = `
    <div class="auth-page">
      <div class="auth-brand">
        <h1>${icon('logo')} Personal Finance Assistant</h1>
        <p>Enterprise-grade money management with AI insights, bill scanning, split expenses, and real-time analytics.</p>
        <div class="auth-features">
          <div class="auth-feature">
            <div class="auth-feature-icon">${icon('analytics')}</div>
            <div><strong>Smart Analytics</strong><br>Health scores, charts, and AI-powered insights</div>
          </div>
          <div class="auth-feature">
            <div class="auth-feature-icon">${icon('bill-scanner')}</div>
            <div><strong>Bill Scanner</strong><br>OCR extraction from receipts and invoices</div>
          </div>
          <div class="auth-feature">
            <div class="auth-feature-icon">${icon('goals')}</div>
            <div><strong>Goals & Budgets</strong><br>Track savings targets and spending limits</div>
          </div>
        </div>
      </div>
      <div class="auth-panel">
        <div class="auth-card card fade-up">
          <div class="auth-tabs" role="tablist">
            <button type="button" id="tab-login" role="tab" aria-selected="${mode === 'login'}" class="${mode === 'login' ? 'active' : ''}">Sign In</button>
            <button type="button" id="tab-register" role="tab" aria-selected="${mode === 'register'}" class="${mode === 'register' ? 'active' : ''}">Sign Up</button>
          </div>
          <form id="auth-form" novalidate>
            <div class="form-group">
              <label for="auth-email">Email</label>
              <input class="form-input" id="auth-email" type="email" placeholder="you@example.com" required autocomplete="email">
            </div>
            ${mode === 'register' ? `
            <div class="form-group">
              <label for="auth-username">Username</label>
              <input class="form-input" id="auth-username" type="text" placeholder="johndoe" required minlength="3" maxlength="30" pattern="[a-zA-Z0-9_]+" autocomplete="username">
              <p style="font-size:.78rem;color:var(--text-muted);margin-top:6px">Letters, numbers, and underscores only</p>
            </div>` : ''}
            <div class="form-group">
              <label for="auth-pass">Password</label>
              <input class="form-input" id="auth-pass" type="password" placeholder="Min 8 characters" required minlength="8" autocomplete="${mode === 'login' ? 'current-password' : 'new-password'}">
            </div>
            <button type="submit" class="btn btn-primary" style="width:100%;margin-top:8px" id="auth-submit">
              ${mode === 'login' ? 'Sign In' : 'Create Account'}
            </button>
          </form>
          <p class="auth-footer">
            ${mode === 'login' ? "Don't have an account? Click Sign Up above." : 'Already registered? Click Sign In above.'}
          </p>
        </div>
      </div>
    </div>`;

    container.querySelector('#tab-login').onclick = () => { mode = 'login'; draw(); };
    container.querySelector('#tab-register').onclick = () => { mode = 'register'; draw(); };
    container.querySelector('#auth-form').onsubmit = handleSubmit;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    const btn = document.getElementById('auth-submit');
    const email = document.getElementById('auth-email').value.trim();
    const password = document.getElementById('auth-pass').value;
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span>';

    try {
      if (mode === 'register') {
        const username = document.getElementById('auth-username').value.trim();
        await api.post('/auth/register', { email, username, password, role: 'USER' });
        toast('Account created! Signing in…', 'success');
      }
      const data = await api.post('/auth/login', { email, password });
      Auth.save({ ...data, email });
      toast('Welcome back!', 'success');
      window.dispatchEvent(new Event('auth-change'));
    } catch (err) {
      toast(err.message, 'error');
      btn.disabled = false;
      btn.textContent = mode === 'login' ? 'Sign In' : 'Create Account';
    }
  }

  draw();
}
