import { describe, it, expect } from 'vitest';
import { mkdtempSync, cpSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

function build(change = false) {
  const dir = mkdtempSync(join(tmpdir(), 'pfa-assets-'));
  for (const file of ['index.html', 'js', 'css', 'assets']) cpSync(resolve(file), join(dir, file), { recursive: true });
  if (change) writeFileSync(join(dir, 'js/utils/api.js'), readFileSync(join(dir, 'js/utils/api.js')) + '\n// changed dependency\n');
  execFileSync('sh', [resolve('build-assets.sh'), dir]);
  return dir;
}

describe('asset publication', () => {
  it('versions the full module graph and app images while keeping API URLs intact', () => {
    const dir = build();
    try {
      const html = readFileSync(join(dir, 'index.html'), 'utf8');
      const prefix = html.match(/\/static\/[a-f0-9]{20}/)[0];
      expect(html).toContain(`${prefix}/css/style.css`);
      expect(html).toContain(`${prefix}/assets/varavu-mark.png`);
      const app = readFileSync(join(dir, prefix, 'js/app.js'), 'utf8');
      expect(app).toContain(`${prefix}/assets/varavu-mark.png`);
      expect(app).toContain("from './utils/api.js'");
      expect(readFileSync(join(dir, prefix, 'js/utils/ui.js'), 'utf8')).toContain('/api/storage/');
    } finally { rmSync(dir, { recursive: true }); }
  });

  it('is deterministic and invalidates entry URLs when a dependency changes', () => {
    const dirs = [build(), build(), build(true)];
    try {
      const versions = dirs.map(dir => readFileSync(join(dir, 'index.html'), 'utf8').match(/\/static\/[a-f0-9]{20}/)[0]);
      expect(versions[0]).toBe(versions[1]);
      expect(versions[2]).not.toBe(versions[0]);
    } finally { dirs.forEach(dir => rmSync(dir, { recursive: true })); }
  });
});
