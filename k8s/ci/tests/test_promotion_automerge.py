"""Check promotion auto-merge enablement without GitHub credentials or network calls."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'enable-promotion-automerge.sh'
FAKE_CURL = '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
case = os.environ['CASE']
sha = os.environ['EXPECTED_HEAD_SHA']
url = next(a for a in args if a.startswith('https://'))
if '/graphql' in url:
    payload = args[args.index('--data-binary') + 1][1:]
    pathlib.Path(os.environ['CAPTURE']).write_text(pathlib.Path(payload).read_text())
    if case == 'graphql_error':
        print(json.dumps({'errors': [{'message': 'Could not resolve to a node'}]}))
    elif case == 'already_enabled':
        print(json.dumps({'errors': [{'message': 'Pull request is already in auto-merge mode'}]}))
    else:
        print(json.dumps({'data': {'enablePullRequestAutoMerge': {'pullRequest': {'number': 42, 'mergeStateStatus': 'BLOCKED'}}}}))
else:
    print(json.dumps({'state': 'open', 'draft': False,
      'commits': 2 if case == 'extra_commits' else 1,
      'node_id': 'PR_node_42',
      'user': {'login': 'person' if case == 'human_pr' else 'github-actions[bot]'},
      'head': {'sha': '0'*40 if case == 'changed_head' else sha,
               'ref': 'feature' if case == 'wrong_branch' else 'ci/tag-bump-123-1',
               'repo': {'full_name': 'outsider/repo' if case == 'fork' else 'owner/repo'}},
      'base': {'ref': 'master', 'repo': {'full_name': 'owner/repo'}}}))
'''


class AutoMergeTests(unittest.TestCase):
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
            capture = root / 'graphql.json'
            env = dict(os.environ, PATH=folder + os.pathsep + os.environ['PATH'],
                       GITHUB_TOKEN='test-token', GITHUB_REPOSITORY='owner/repo',
                       GITHUB_REF_NAME='master', PR_NUMBER='42', PR_NODE_ID='PR_node_42',
                       SOURCE_SHA=source, EXPECTED_HEAD_SHA=git('rev-parse', 'HEAD'),
                       CASE=case, CAPTURE=str(capture),
                       GITHUB_STEP_SUMMARY=str(root / 'summary.md'))
            result = subprocess.run(['bash', str(HELPER)], cwd=root, env=env,
                                    capture_output=True, text=True)
            request = json.loads(capture.read_text()) if capture.exists() else None
            return result, request

    def test_enables_automerge_for_the_validated_sha(self):
        result, request = self.run_case('success')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(request['variables'], {'id': 'PR_node_42'})
        self.assertIn('enablePullRequestAutoMerge', request['query'])
        self.assertIn('mergeMethod:MERGE', request['query'])
        self.assertNotIn('test-token', result.stdout + result.stderr)

    def test_already_enabled_is_success(self):
        result, request = self.run_case('already_enabled')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIsNotNone(request)

    def test_rejects_unvalidated_or_unready_promotions(self):
        for case in ('source_edit', 'wrong_image', 'changed_head', 'human_pr',
                     'wrong_branch', 'fork', 'extra_commits'):
            with self.subTest(case=case):
                result, request = self.run_case(case)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIsNone(request)

    def test_graphql_failure_fails(self):
        result, request = self.run_case('graphql_error')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNotNone(request)


if __name__ == '__main__':
    unittest.main()
