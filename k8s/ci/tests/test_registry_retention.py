"""Run registry retention against simulated manifests without deleting images."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'registry-gc.sh'
FAKE_CURL = '''#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
url = next(a for a in args if a.startswith('https://'))
path = url.split('/v2/', 1)[1]
fixtures = json.loads(os.environ['REGISTRY_FIXTURES'])
if any('I' in a for a in args if a.startswith('-')):
    tag = path.rsplit('/', 1)[1]
    print('Docker-Content-Digest: sha256:' + tag + '\\r')
else:
    print(json.dumps(fixtures[path]))
'''


class RegistryRetentionTests(unittest.TestCase):
    def run_retention(self, dates, index=False):
        fixtures = {'': {}, '_catalog': {'repositories': ['finance/auth-service']},
                    'finance/auth-service/tags/list': {'tags': list(dates)}}
        for tag, date in dates.items():
            manifest = {'config': {'digest': 'sha256:config-' + tag}}
            if index:
                fixtures['finance/auth-service/manifests/' + tag] = {
                    'manifests': [{'digest': 'sha256:child-' + tag}]}
                fixtures['finance/auth-service/manifests/sha256:child-' + tag] = manifest
            else:
                fixtures['finance/auth-service/manifests/' + tag] = manifest
            fixtures['finance/auth-service/blobs/sha256:config-' + tag] = {'created': date}
        with tempfile.TemporaryDirectory() as folder:
            curl = Path(folder) / 'curl'
            curl.write_text(FAKE_CURL)
            curl.chmod(0o755)
            env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                       REGISTRY='https://registry.invalid', REGISTRY_CA='unused',
                       REGISTRY_CERT='unused', REGISTRY_KEY='unused',
                       REGISTRY_FIXTURES=json.dumps(fixtures))
            result = subprocess.run(['bash', str(HELPER), '--dry-run', '--retention-only',
                                     '--keep=2'], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_mixed_timezones_keep_new_image_for_manifests_and_indexes(self):
        dates = {'oldest': '2026-11-26T13:52:00.714275803+05:30',
                 'previous': '2026-11-26T14:00:02.517152257+05:30',
                 'fresh': '2026-11-26T13:11:47.866570718Z'}
        for index in (False, True):
            with self.subTest(index=index):
                output = self.run_retention(dates, index=index)
                self.assertIn('delete finance/auth-service:oldest ', output)
                self.assertNotIn('delete finance/auth-service:fresh ', output)
                self.assertNotIn('delete finance/auth-service:previous ', output)

    def test_negative_offset_is_converted_to_utc(self):
        output = self.run_retention({'oldest': '2026-11-26T10:00:00Z',
                                     'previous': '2026-11-26T11:00:00Z',
                                     'fresh': '2026-11-26T08:00:00-05:00'})
        self.assertIn('delete finance/auth-service:oldest ', output)
        self.assertNotIn('delete finance/auth-service:fresh ', output)

    def test_unreadable_date_preserves_repository(self):
        for date in (None, 'not-a-date'):
            with self.subTest(date=date):
                output = self.run_retention({'unknown1': date, 'unknown2': date,
                                             'fresh': '2026-11-26T13:11:47Z'})
                self.assertIn('skipping retention for this repository', output)
                self.assertNotIn('    delete ', output)


if __name__ == '__main__':
    unittest.main()
