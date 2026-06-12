import { beforeEach, vi } from 'vitest';

global.Chart = vi.fn().mockImplementation(() => ({
  destroy: vi.fn(),
  update: vi.fn(),
}));
Chart.defaults = { color: '', borderColor: '', font: { family: '' } };

beforeEach(() => {
  localStorage.clear();
  document.body.innerHTML = '';
  document.head.innerHTML = '';
  global.fetch = vi.fn();
  global.confirm = vi.fn(() => true);
});
