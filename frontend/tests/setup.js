import { beforeEach, vi } from 'vitest';

global.Chart = vi.fn().mockImplementation(() => ({
  destroy: vi.fn(),
  update: vi.fn(),
}));
Chart.defaults = { color: '', borderColor: '', font: { family: '' } };

// Mock the lazy loader so tests don't try to load CDN scripts in jsdom
vi.mock('../js/utils/loader.js', () => ({
  loadScript: vi.fn().mockResolvedValue(undefined),
  loadStylesheet: vi.fn().mockResolvedValue(undefined),
  loadChartJs: vi.fn().mockResolvedValue(undefined),
  loadCropperJs: vi.fn().mockResolvedValue(undefined),
  loadFlatpickr: vi.fn().mockResolvedValue(undefined),
  loadTomSelect: vi.fn().mockResolvedValue(undefined),
}));

beforeEach(() => {
  localStorage.clear();
  document.body.innerHTML = '';
  document.head.innerHTML = '';
  global.fetch = vi.fn();
  global.confirm = vi.fn(() => true);
});
