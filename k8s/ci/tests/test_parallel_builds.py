"""Run fake build clients to verify bounded parallelism and failure handling."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'build-services.py'
spec = importlib.util.spec_from_file_location('parallel_builds', SCRIPT)
builds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builds)
FAKE = '''#!/usr/bin/env python3
import json, os, pathlib, sys, time
args=sys.argv[1:]
output=args[args.index('--output')+1]
service=output.split('/finance/')[1].split(':')[0]
path=pathlib.Path(os.environ['EVENTS'])
def event(kind):
    fd=os.open(path,os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
    os.write(fd,(json.dumps({'service':service,'event':kind})+'\\n').encode());os.close(fd)
event('start');time.sleep(.15)
if service==os.environ.get('FAIL_SERVICE'):
    event('end');sys.exit(1)
metadata=pathlib.Path(args[args.index('--metadata-file')+1])
metadata.write_text(json.dumps({'containerimage.digest':'sha256:'+'a'*64}))
event('end')
'''


class ParallelBuildTests(unittest.TestCase):
    def run_build(self, services, failed=''):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            executable=root/'buildctl'
            executable.write_text(FAKE)
            executable.chmod(0o755)
            events=root/'events'
            env=dict(os.environ,GITHUB_SHA='a'*40,PUSH_REGISTRY='registry.test:5001',
                     BUILDKIT_TLS_DIR='/certs',EVENTS=str(events),FAIL_SERVICE=failed)
            result=builds.build_images(services,env,root,root/'digests',2,str(executable))
            rows=[json.loads(line) for line in events.read_text().splitlines()]
            metadata=sorted(p.name for p in (root/'digests').glob('*.json'))
            return result,rows,metadata

    def test_two_builds_overlap_and_ocr_runs_alone(self):
        result,rows,metadata=self.run_build(['auth-service','query-service','command-service','ocr-service'])
        self.assertEqual(result,0)
        active=set();peak=0
        for row in rows:
            if row['event']=='start':
                if row['service']=='ocr-service':self.assertFalse(active)
                else:self.assertNotIn('ocr-service',active)
                active.add(row['service']);peak=max(peak,len(active))
            else:active.remove(row['service'])
        self.assertEqual(peak,2)
        self.assertEqual(len(metadata),4)

    def test_failed_build_has_no_promotion_metadata(self):
        result,_,metadata=self.run_build(['auth-service','query-service'],'auth-service')
        self.assertEqual(result,1)
        self.assertEqual(metadata,['query-service.json'])

    def test_unknown_services_and_excessive_concurrency_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for services,jobs in [(['../other'],2),(['auth-service'],3),(['auth-service']*2,2)]:
                with self.assertRaises(ValueError):
                    builds.build_images(services,{},Path(folder),Path(folder)/'out',jobs)

    def test_source_sha_must_be_exact(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                builds.build_images(['auth-service'],{'GITHUB_SHA':'master'},Path(folder),folder)


if __name__=='__main__':unittest.main()
