import { api, Auth, toast } from '../utils/api.js';

export function renderAuth(container) {
  let mode = 'login';

  function draw() {
    container.innerHTML = `
    <div class="auth-page">
      <div class="auth-card card fade-up">
        <div class="logo">
          <h1>💰 PFA</h1>
          <p>Personal Finance Assistant</p>
        </div>
        <div class="auth-tabs">
          <button id="tab-login" class="${mode==='login'?'active':''}">Sign In</button>
          <button id="tab-register" class="${mode==='register'?'active':''}">Sign Up</button>
        </div>
        <form id="auth-form">
          <div class="form-group">
            <label>Email</label>
            <input class="form-input" id="auth-email" type="email" placeholder="you@example.com" required>
          </div>
          <div class="form-group">
            <label>Password</label>
            <input class="form-input" id="auth-pass" type="password" placeholder="Min 8 characters" required minlength="8">
          </div>
          <button type="submit" class="btn btn-primary" style="width:100%;justify-content:center;margin-top:8px" id="auth-submit">
            ${mode === 'login' ? 'Sign In' : 'Create Account'}
          </button>
        </form>
        <p class="auth-footer">
          ${mode === 'login' ? "Don't have an account? Click Sign Up" : 'Already have an account? Click Sign In'}
        </p>
      </div>
    </div>`;

    container.querySelector('#tab-login').onclick = () => { mode = 'login'; draw(); };
    container.querySelector('#tab-register').onclick = () => { mode = 'register'; draw(); };
    container.querySelector('#auth-form').onsubmit = handleSubmit;
  }

  async function handleSubmit(e) {
    e.preventDefault();
    const btn = document.getElementById('auth-submit');
    const email = document.getElementById('auth-email').value;
    const password = document.getElementById('auth-pass').value;
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span>';

    try {
      if (mode === 'register') {
        await api.post('/auth/register', { email, password, role: 'USER' });
        toast('Account created! Signing in…', 'success');
      }
      const data = await api.post('/auth/login', { email, password });
      Auth.save(data);
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
