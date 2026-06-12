import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Auth, api, toast, abortPendingRequests } from '../js/utils/api.js';

function mockFetchJson(data, status = 200) {
  global.fetch.mockResolvedValueOnce({
    status,
    ok: status >= 200 && status < 300,
    text: async () => JSON.stringify(data),
  });
}

describe('Auth', () => {
  beforeEach(() => {
    Auth.clear();
  });

  it('saves and retrieves session data', () => {
    Auth.save({ token: 'tok', refreshToken: 'ref', userId: 'uid-1', email: 'a@b.com' });
    expect(Auth.getToken()).toBe('tok');
    expect(Auth.getRefresh()).toBe('ref');
    expect(Auth.getUserId()).toBe('uid-1');
    expect(Auth.getEmail()).toBe('a@b.com');
    expect(Auth.isLoggedIn()).toBe(true);
  });

  it('clears session on logout', () => {
    Auth.save({ token: 'tok', refreshToken: 'ref', userId: 'uid', email: 'x@y.com' });
    Auth.clear();
    expect(Auth.isLoggedIn()).toBe(false);
    expect(Auth.getToken()).toBeNull();
  });
});

describe('api client', () => {
  beforeEach(() => {
    Auth.save({ token: 'jwt-token', refreshToken: 'refresh', userId: 'user-42', email: 'test@test.com' });
    global.fetch.mockReset();
  });

  it('sends Authorization and X-User-Id headers on GET', async () => {
    mockFetchJson({ totalIncome: 1000 });
    const result = await api.get('/upsert/summary', { userId: 'user-42' });
    expect(result.totalIncome).toBe(1000);

    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toContain('/api/upsert/summary');
    expect(opts.headers.Authorization).toBe('Bearer jwt-token');
    expect(opts.headers['X-User-Id']).toBe('user-42');
  });

  it('posts JSON body with Content-Type', async () => {
    mockFetchJson({ id: 1 }, 201);
    await api.post('/upsert/create', { name: 'Coffee', amount: 5 });

    const [, opts] = global.fetch.mock.calls[0];
    expect(opts.method).toBe('POST');
    expect(opts.headers['Content-Type']).toBe('application/json');
    expect(JSON.parse(opts.body)).toMatchObject({ name: 'Coffee', amount: 5 });
  });

  it('throws with server error message', async () => {
    global.fetch.mockResolvedValueOnce({
      status: 400,
      ok: false,
      text: async () => JSON.stringify({ message: 'Invalid amount' }),
    });
    await expect(api.get('/upsert/entries')).rejects.toThrow('Invalid amount');
  });

  it('includes validation details in error message', async () => {
    global.fetch.mockResolvedValueOnce({
      status: 400,
      ok: false,
      text: async () => JSON.stringify({
        message: 'Validation failed',
        details: ['createdBy: must not be null'],
      }),
    });
    await expect(api.post('/upsert/groups', {})).rejects.toThrow('createdBy: must not be null');
  });

  it('clears auth and dispatches event on 401', async () => {
    // Remove refresh token so the 401 handler goes directly to clear+dispatch
    localStorage.removeItem('pfa_refresh');
    const handler = vi.fn();
    window.addEventListener('auth-expired', handler);

    global.fetch.mockResolvedValueOnce({ status: 401, ok: false, text: async () => '{}' });
    await expect(api.get('/upsert/summary')).rejects.toThrow('Session expired');
    expect(Auth.isLoggedIn()).toBe(false);
    expect(handler).toHaveBeenCalled();
  });
});

describe('toast', () => {
  it('appends toast element to container', () => {
    toast('Hello', 'success');
    const el = document.querySelector('.toast-success');
    expect(el).toBeTruthy();
    expect(el.textContent).toContain('Hello');
  });
});
