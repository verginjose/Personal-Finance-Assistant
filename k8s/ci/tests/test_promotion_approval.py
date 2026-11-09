import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'approve-promotion-pr.sh'
FAKE = '''#!/usr/bin/env python3
import json,os,pathlib,sys
args=sys.argv[1:]
if '--request' in args:
    payload=args[args.index('--data-binary')+1][1:]
    pathlib.Path(os.environ['CAPTURE']).write_text(pathlib.Path(payload).read_text())
    print('{}')
else:
    print(json.dumps({'state':'open','head':{'sha':os.environ['EXPECTED_HEAD_SHA'], 'ref':'ci/tag-bump-123-1','repo':{'full_name':'owner/repo'}}}))
'''


class ApprovalTests(unittest.TestCase):
    def run_case(self, unsafe=False, token=True):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def git(*args):
                return subprocess.check_output(['git', *args], cwd=root, text=True, stderr=subprocess.DEVNULL).strip()
            git('init', '-q')
            git('config', 'user.name', 'Test')
            git('config', 'user.email', 'test@example.invalid')
            path=root/'k8s/apps/ocr-service.yaml'
            path.parent.mkdir(parents=True)
            path.write_text('image: 127.0.0.1:30500/finance/ocr-service:aaaaaaaaaaaa\n')
            git('add', '.')
            git('commit', '-qm', 'base')
            if unsafe:
                (root/'unexpected.py').write_text('print("unexpected")\n')
            else:
                path.write_text('image: 127.0.0.1:30500/finance/ocr-service:bbbbbbbbbbbb\n')
            git('add', '.')
            git('commit', '-qm', 'promotion')
            curl=root/'curl'
            curl.write_text(FAKE)
            curl.chmod(0o755)
            env=dict(os.environ, PATH=folder+os.pathsep+os.environ['PATH'],
                     GITHUB_TOKEN='creator', REVIEW_TOKEN='reviewer' if token else '',
                     GITHUB_REPOSITORY='owner/repo', PR_NUMBER='42',
                     GITHUB_STEP_SUMMARY=str(root/'summary.md'),
                     EXPECTED_HEAD_SHA=git('rev-parse','HEAD'), CAPTURE=str(root/'review.json'))
            result=subprocess.run(['bash',str(HELPER)],cwd=root,env=env,capture_output=True,text=True)
            review=json.loads((root/'review.json').read_text()) if (root/'review.json').exists() else None
            return result,review

    def test_approves_only_valid_generated_manifest_commit(self):
        result, review=self.run_case()
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(review['event'],'APPROVE')
        self.assertEqual(len(review['commit_id']),40)

    def test_rejects_source_code_change(self):
        result,review=self.run_case(unsafe=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIsNone(review)

    def test_missing_reviewer_leaves_manual_review(self):
        result,review=self.run_case(token=False)
        self.assertEqual(result.returncode,0)
        self.assertIsNone(review)
        self.assertIn('manual review',result.stdout)


if __name__ == '__main__':
    unittest.main()
