import { api, Auth, toast } from '../utils/api.js?v=2026110417';
import { icon } from '../utils/icons.js?v=2026110417';
import { currencyOptionHtml } from '../utils/ui.js?v=2026110417';

/** Best-effort default from the browser locale; the user can always change it. */
function localCurrencyGuess() {
  const locale = (navigator.language || 'en-IN').toUpperCase();
  if (locale.endsWith('-US')) return 'USD';
  if (locale.endsWith('-GB')) return 'GBP';
  if (locale.endsWith('-AE')) return 'AED';
  if (locale.endsWith('-AU')) return 'AUD';
  if (locale.endsWith('-CA')) return 'CAD';
  if (locale.endsWith('-SG')) return 'SGD';
  if (locale.endsWith('-JP')) return 'JPY';
  if (/-(DE|FR|ES|IT|NL|IE|PT|AT|BE|FI|GR|SK|SI|LT|LV|EE|LU|CY|MT)$/.test(locale)) return 'EUR';
  return 'INR';
}

export function renderAuth(container) {
  let mode = 'login';

  function draw() {
    container.innerHTML = `
    <div class="auth-page">
      <div class="auth-brand">
        <h1><span class="logo-mark">P</span> Personal Finance Assistant</h1>
        <p>Every account, receipt and shared bill in one calm ledger.</p>
        <div class="auth-features">
          <div class="auth-feature">
            <div class="auth-feature-icon">${icon('analytics')}</div>
            <div><strong>Clear numbers</strong><br>Spending, income and what is left, without the noise.</div>
          </div>
          <div class="auth-feature">
            <div class="auth-feature-icon">${icon('bill-scanner')}</div>
            <div><strong>Receipt reading</strong><br>Photograph a bill and check the extracted details.</div>
          </div>
          <div class="auth-feature">
            <div class="auth-feature-icon">${icon('goals')}</div>
            <div><strong>Goals and budgets</strong><br>Save toward something specific, with an honest pace.</div>
          </div>
        </div>
      </div>
      <div class="auth-panel">
        <div class="auth-card card fade-up">
          <div class="auth-tabs" role="tablist">
            <button type="button" id="tab-login" role="tab" aria-selected="${mode === 'login'}" class="${mode === 'login' ? 'active' : ''}">Sign in</button>
            <button type="button" id="tab-register" role="tab" aria-selected="${mode === 'register'}" class="${mode === 'register' ? 'active' : ''}">Create account</button>
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
              <p class="form-help">Letters, numbers and underscores only.</p>
            </div>
            <div class="form-group">
              <label for="auth-currency">Default currency</label>
              <select class="form-select" id="auth-currency" required>
                ${currencyOptionHtml(localCurrencyGuess())}
              </select>
              <p class="form-help">Receipts and reports are converted to this currency.</p>
            </div>` : ''}
            <div class="form-group">
              <label for="auth-pass">Password</label>
              <input class="form-input" id="auth-pass" type="password" placeholder="Min 8 characters" required minlength="8" autocomplete="${mode === 'login' ? 'current-password' : 'new-password'}">
            </div>
            <button type="submit" class="btn btn-primary btn-block" style="margin-top:8px" id="auth-submit">
              ${mode === 'login' ? 'Sign in' : 'Create account'}
            </button>
          </form>
          <p class="auth-footer">
            ${mode === 'login' ? 'No account yet? Choose Create account above.' : 'Already registered? Choose Sign in above.'}
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
        const defaultCurrency = document.getElementById('auth-currency')?.value || 'INR';
        await api.post('/auth/register', { email, username, password, role: 'USER', defaultCurrency });
        toast('Account created! Signing in…', 'success');
      }
      const data = await api.post('/auth/login', { email, password });
      Auth.save({ ...data, email });
      toast('Welcome back!', 'success');
      window.dispatchEvent(new Event('auth-change'));
    } catch (err) {
      toast(err.message, 'error');
      btn.disabled = false;
      btn.textContent = mode === 'login' ? 'Sign in' : 'Create account';
    }
  }

  draw();
}
