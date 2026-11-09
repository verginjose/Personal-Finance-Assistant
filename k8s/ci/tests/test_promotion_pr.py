"""Exercise the PR helper without network calls or GitHub credentials."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'open-promotion-pr.sh'
FAKE_CURL = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
post = '--request' in args and args[args.index('--request') + 1] == 'POST'
if post:
    payload = args[args.index('--data-binary') + 1][1:]
    pathlib.Path(os.environ['REQUEST_CAPTURE']).write_text(pathlib.Path(payload).read_text())
    if os.environ['CASE'] == 'denied':
        print(json.dumps({'message': 'GitHub Actions is not permitted to create pull requests'}))
        sys.exit(22)
    print(json.dumps({'html_url': 'https://github.com/owner/repo/pull/42'}))
else:
    print(json.dumps([{'html_url': 'https://github.com/owner/repo/pull/41'}] if os.environ['CASE'] == 'existing' else []))
'''


class PromotionTests(unittest.TestCase):
    def run_helper(self, case):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        curl = root / 'curl'
        curl.write_text(FAKE_CURL)
        curl.chmod(0o755)
        capture, summary = root / 'request.json', root / 'summary.md'
        env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                   GITHUB_TOKEN='test-token', GITHUB_REPOSITORY='owner/repo',
                   GITHUB_REF_NAME='k8s/local-deployment', BRANCH='ci/tag-bump-123-1',
                   TAG='abc123', GITHUB_SHA='abc123456', GITHUB_RUN_ID='123',
                   CASE=case, REQUEST_CAPTURE=str(capture), GITHUB_STEP_SUMMARY=str(summary))
        env['GITHUB_OUTPUT'] = str(root / 'outputs')
        result = subprocess.run(['bash', str(HELPER)], env=env, text=True, capture_output=True)
        return result, capture, summary

    def test_creates_pr_for_correct_branch(self):
        result, capture, summary = self.run_helper('new')
        self.assertEqual(result.returncode, 0, result.stderr)
        payload = json.loads(capture.read_text())
        self.assertEqual(payload['head'], 'ci/tag-bump-123-1')
        self.assertEqual(payload['base'], 'k8s/local-deployment')
        self.assertEqual(payload['title'], 'chore(deploy): promote built images to abc123')
        self.assertIn('\n\n', payload['body'])
        self.assertIn('pull/42', summary.read_text())
        self.assertNotIn('test-token', result.stdout + result.stderr)

    def test_existing_pr_is_reused(self):
        result, capture, summary = self.run_helper('existing')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(capture.exists())
        self.assertIn('pull/41', summary.read_text())

    def test_permission_failure_fails_job_with_setup_hint(self):
        result, _, summary = self.run_helper('denied')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Allow GitHub Actions to create', result.stderr)
        self.assertFalse(summary.exists())


if __name__ == '__main__':
    unittest.main()
