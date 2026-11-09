"""Check promotion merge guards without GitHub credentials or network calls."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'merge-promotion-pr.sh'
FAKE_CURL = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
case = os.environ['CASE']
sha = os.environ['EXPECTED_HEAD_SHA']
url = next(a for a in args if a.startswith('https://'))
if '--request' in args:
    payload = args[args.index('--data-binary') + 1][1:]
    pathlib.Path(os.environ['CAPTURE']).write_text(pathlib.Path(payload).read_text())
    if case == 'merge_race':
        print(json.dumps({'message': 'Head branch was modified'}))
        sys.exit(22)
    print(json.dumps({'merged': case != 'merge_refused'}))
elif '/check-runs' in url:
    count = pathlib.Path(os.environ['COUNTER'])
    attempt = int(count.read_text()) if count.exists() else 0
    count.write_text(str(attempt + 1))
    pending = case == 'pending' or (case == 'wait_then_pass' and attempt == 0)
    runs = [] if case == 'no_checks' else [{'status': 'in_progress' if pending else 'completed', 'conclusion': 'failure' if case == 'failed_check' else 'success'}]
    print(json.dumps({'total_count': len(runs), 'check_runs': runs}))
elif '/status?' in url:
    print(json.dumps({'total_count': 0 if case != 'failed_status' else 1, 'state': 'failure' if case == 'failed_status' else 'pending', 'statuses': [] if case != 'failed_status' else [{}]}))
elif '/reviews?' in url:
    reviews = [{'id': 1, 'user': {'login': 'reviewer'}, 'state': 'COMMENTED' if case == 'unapproved' else 'APPROVED', 'commit_id': '0'*40 if case == 'stale_review' else sha}]
    if case == 'changes_requested':
        reviews.append({'id': 2, 'user': {'login': 'reviewer'}, 'state': 'CHANGES_REQUESTED', 'commit_id': sha})
    print(json.dumps(reviews))
else:
    print(json.dumps({'state': 'open', 'draft': False, 'commits': 2 if case == 'extra_commits' else 1,
      'user': {'login': 'person' if case == 'human_pr' else 'github-actions[bot]'},
      'head': {'sha': '0'*40 if case == 'changed_head' else sha, 'ref': 'feature' if case == 'wrong_branch' else 'ci/tag-bump-123-1', 'repo': {'full_name': 'outsider/repo' if case == 'fork' else 'owner/repo'}},
      'base': {'ref': 'master', 'repo': {'full_name': 'owner/repo'}},
      'mergeable': True, 'mergeable_state': 'blocked' if case == 'blocked' else 'clean'}))
'''


class MergeTests(unittest.TestCase):
    def run_case(self, case):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True,
                                               stderr=subprocess.DEVNULL).strip()
            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.invalid')
            path = root / 'k8s/apps/ocr-service.yaml'
            path.parent.mkdir(parents=True)
            path.write_text('image: 127.0.0.1:30500/finance/ocr-service:aaaaaaaaaaaa\n')
            git('add', '.')
            git('commit', '-qm', 'source')
            source = git('rev-parse', 'HEAD')
            tag = 'bbbbbbbbbbbb' if case == 'wrong_image' else source[:12]
            path.write_text(f'image: 127.0.0.1:30500/finance/ocr-service:{tag}\n')
            if case == 'source_edit':
                (root / 'source.py').write_text('print("unexpected")\n')
            git('add', '.')
            git('commit', '-qm', 'promotion')
            curl = root / 'curl'
            curl.write_text(FAKE_CURL)
            curl.chmod(0o755)
            capture = root / 'merge.json'
            env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                       GITHUB_TOKEN='test-token', GITHUB_REPOSITORY='owner/repo',
                       GITHUB_REF_NAME='master', PR_NUMBER='42', SOURCE_SHA=source,
                       EXPECTED_HEAD_SHA=git('rev-parse', 'HEAD'), CASE=case,
                       CAPTURE=str(capture), COUNTER=str(root / 'polls'),
                       GITHUB_STEP_SUMMARY=str(root / 'summary.md'),
                       MERGE_POLL_ATTEMPTS='2', MERGE_POLL_SECONDS='0')
            result = subprocess.run(['bash', str(HELPER)], cwd=root, env=env,
                                    capture_output=True, text=True)
            request = json.loads(capture.read_text()) if capture.exists() else None
            return result, request, env['EXPECTED_HEAD_SHA']

    def test_merges_only_the_validated_sha(self):
        result, request, sha = self.run_case('success')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(request, {'sha': sha, 'merge_method': 'merge'})
        self.assertNotIn('test-token', result.stdout + result.stderr)

    def test_waits_for_checks_before_merging(self):
        result, request, _ = self.run_case('wait_then_pass')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(request)
        self.assertIn('Waiting for promotion', result.stdout)

    def test_rejects_unvalidated_or_unready_promotions(self):
        for case in ('source_edit', 'wrong_image', 'changed_head', 'human_pr',
                     'wrong_branch', 'fork', 'extra_commits', 'unapproved',
                     'stale_review', 'changes_requested', 'pending', 'no_checks',
                     'failed_check', 'failed_status', 'blocked'):
            with self.subTest(case=case):
                result, request, _ = self.run_case(case)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIsNone(request)

    def test_merge_refusal_or_head_race_fails(self):
        for case in ('merge_refused', 'merge_race'):
            with self.subTest(case=case):
                result, request, sha = self.run_case(case)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(request['sha'], sha)


if __name__ == '__main__':
    unittest.main()
