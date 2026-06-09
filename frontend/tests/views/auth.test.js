import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderAuth } from '../../js/views/auth.js';
import { Auth } from '../../js/utils/api.js';

function mockFetchJson(data, status = 200) {
  global.fetch.mockResolvedValueOnce({
    status,
    ok: status >= 200 && status < 300,
    text: async () => JSON.stringify(data),
  });
}

describe('renderAuth', () => {
  let container;

  beforeEach(() => {
    Auth.clear();
    container = document.createElement('div');
    document.body.appendChild(container);
    global.fetch.mockReset();
  });

  it('renders sign-in form by default', () => {
    renderAuth(container);
    expect(container.querySelector('#auth-form')).toBeTruthy();
    expect(container.querySelector('#tab-login').classList.contains('active')).toBe(true);
    expect(container.querySelector('#auth-submit').textContent).toContain('Sign In');
  });

  it('switches to register tab', () => {
    renderAuth(container);
    container.querySelector('#tab-register').click();
    expect(container.querySelector('#tab-register').classList.contains('active')).toBe(true);
    expect(container.querySelector('#auth-submit').textContent).toContain('Create Account');
  });

  it('submits login and saves session', async () => {
    const authChange = vi.fn();
    window.addEventListener('auth-change', authChange);

    mockFetchJson({ token: 't', refreshToken: 'r', userId: 'u1' });
    renderAuth(container);

    container.querySelector('#auth-email').value = 'user@example.com';
    container.querySelector('#auth-pass').value = 'password1';
    container.querySelector('#auth-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));

    await vi.waitFor(() => expect(Auth.isLoggedIn()).toBe(true));
    expect(Auth.getEmail()).toBe('user@example.com');
    expect(authChange).toHaveBeenCalled();
  });
});
