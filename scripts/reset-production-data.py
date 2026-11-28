#!/usr/bin/env python3
"""Inventory or explicitly erase PFA application data, retaining configuration.

Run inventory first. --execute requires --acknowledge-delete-all-pfa-data.
On failure workloads stay paused; --restore-state can restore saved replicas.
No credentials or application row contents are written to reports/state files.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time

SCHEMAS = ('activity', 'auth', 'finance', 'groups')
APPS = ('pfa-apps', 'pfa-edge', 'pfa-infra', 'pfa-system')
WORKLOADS = ('auth-service', 'command-service', 'query-service',
             'ocr-service', 'frontend', 'edge-router', 'cloudflared', 'prometheus', 'grafana')


class Reset:
    def __init__(self, args):
        self.args = args
        self.base = ['kubectl', '--kubeconfig', args.kubeconfig]

    def kube(self, ns, *args, stdin=None):
        result = subprocess.run(self.base + ['-n', ns, *args], input=stdin,
                                capture_output=True, text=True, timeout=150)
        if result.returncode:
            # Commands inside pods resolve credentials from their own env;
            # avoid echoing stderr/command expansions into a public report.
            raise RuntimeError(f'Kubernetes operation failed in {ns}: {args[0]}')
        return result.stdout.strip()

    def pod(self, name, script, *args):
        return self.kube('pfa', 'exec', name, '--', 'sh', '-c', script, 'sh', *args)

    def sql(self, query, replica=False):
        return self.pod('postgres-replica-0' if replica else 'postgres-primary-0',
                        'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"', query)

    def ch(self, query):
        return self.pod('clickhouse-0', 'exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "$1"', query)

    def redis(self, *args):
        return self.pod('redis-0', 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"', *args)

    def mc(self, operation, bucket=''):
        script = '''set -eu
        folder=$(mktemp -d)
        trap 'rm -rf "$folder"' EXIT
        mc --config-dir "$folder" alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
        if [ "$1" = inventory ]; then
          mc --config-dir "$folder" ls --recursive --json --versions "local/$2"
        else
          mc --config-dir "$folder" rm --recursive --force --versions "local/$2" >/dev/null
        fi'''
        return self.pod('minio-0', script, operation, bucket)

    def tables(self):
        raw = self.sql("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1")
        names = raw.splitlines()
        for name in names:
            if not re.fullmatch(r'[a-z_]+\.[a-z_]+', name) or name.split('.')[0] not in SCHEMAS:
                raise RuntimeError('Unexpected database table; refusing a partial/overbroad reset')
        if 'auth.users' not in names or 'finance.transaction_entries' not in names:
            raise RuntimeError('Expected PFA schema is missing')
        return names

    def inventory(self):
        tables = {name: int(self.sql('SELECT count(*) FROM ' + name)) for name in self.tables()}
        buckets = {}
        for bucket in ('receipts', 'profiles'):
            objects = [json.loads(line) for line in self.mc('inventory', bucket).splitlines() if line]
            buckets[bucket] = sum(item.get('type') == 'file' for item in objects)
        queues = json.loads(self.kube('pfa', 'exec', 'rabbitmq-0', '--', 'rabbitmqctl', 'list_queues',
                                     'name', 'messages_ready', 'messages_unacknowledged', '--formatter=json'))
        if any(q['name'] not in ('ocr-jobs', 'ocr-jobs-dead') for q in queues):
            raise RuntimeError('Unexpected application queue; inspect before resetting')
        logs = [json.loads(line) for line in self.ch("SELECT database,name,total_rows FROM system.tables WHERE database='default' OR (database='system' AND engine LIKE '%MergeTree%' AND name LIKE '%log%') FORMAT JSONEachRow").splitlines()]
        return dict(postgres=tables, minio=buckets, redis_keys=int(self.redis('DBSIZE')), rabbitmq=queues,
                    clickhouse=logs)

    def pause(self):
        state = dict(app_annotations={}, replicas={}, vector_selector=None)
        for name in APPS:
            app = json.loads(self.kube('argocd', 'get', 'application', name, '-o', 'json'))
            state['app_annotations'][name] = app['metadata'].get('annotations', {}).get('argocd.argoproj.io/skip-reconcile')
        for name in WORKLOADS:
            d = json.loads(self.kube('pfa', 'get', 'deployment', name, '-o', 'json'))
            state['replicas'][name] = d['spec'].get('replicas', 1)
        vector = json.loads(self.kube('pfa-system', 'get', 'daemonset', 'vector', '-o', 'json'))
        state['vector_selector'] = vector['spec']['template']['spec'].get('nodeSelector')
        path = Path(self.args.state)
        if path.exists():
            raise RuntimeError('State file exists; inspect/restore it before another reset')
        path.write_text(json.dumps(state, indent=2))
        path.chmod(0o600)
        for name in APPS:
            self.kube('argocd', 'annotate', 'application', name, 'argocd.argoproj.io/skip-reconcile=true', '--overwrite')
        # Close public ingress before stopping writers/consumers.
        for name in ('cloudflared', 'frontend', 'edge-router'):
            self.kube('pfa', 'scale', 'deployment/' + name, '--replicas=0')
        for name in WORKLOADS:
            self.kube('pfa', 'scale', 'deployment/' + name, '--replicas=0')
        self.kube('pfa-system', 'patch', 'daemonset', 'vector', '--type=merge', '-p',
                  json.dumps({'spec': {'template': {'spec': {'nodeSelector': {'pfa-data-reset-paused': 'true'}}}}}))
        for _ in range(60):
            pods = json.loads(self.kube('pfa', 'get', 'pods', '-o', 'json'))['items']
            if not any(p['metadata'].get('labels', {}).get('app') in WORKLOADS for p in pods):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Application pods did not stop; no data was erased')
        for _ in range(30):
            pods = json.loads(self.kube('pfa-system', 'get', 'pods', '-l', 'app=vector', '-o', 'json'))['items']
            if not pods:
                return
            time.sleep(1)
        raise RuntimeError('Log collector did not stop')

    def restore(self):
        state = json.loads(Path(self.args.state).read_text())
        for name, replicas in state['replicas'].items():
            if name not in ('cloudflared', 'frontend', 'edge-router'):
                self.kube('pfa', 'scale', 'deployment/' + name, '--replicas=' + str(replicas))
        for name in ('auth-service', 'command-service', 'query-service', 'ocr-service'):
            self.kube('pfa', 'rollout', 'status', 'deployment/' + name, '--timeout=120s')
        self.kube('pfa-system', 'patch', 'daemonset', 'vector', '--type=json', '-p',
                  json.dumps([{'op':'replace', 'path':'/spec/template/spec/nodeSelector', 'value':state['vector_selector'] or {}}]))
        for name in ('edge-router', 'frontend', 'cloudflared'):
            self.kube('pfa', 'scale', 'deployment/' + name, '--replicas=' + str(state['replicas'][name]))
            self.kube('pfa', 'rollout', 'status', 'deployment/' + name, '--timeout=120s')
        for name, previous in state['app_annotations'].items():
            annotation = 'argocd.argoproj.io/skip-reconcile-' if previous is None else 'argocd.argoproj.io/skip-reconcile=' + previous
            self.kube('argocd', 'annotate', 'application', name, annotation, '--overwrite')
            self.kube('argocd', 'annotate', 'application', name, 'argocd.argoproj.io/refresh=normal', '--overwrite')

    def clear_prometheus(self):
        d = json.loads(self.kube('pfa', 'get', 'deployment', 'prometheus', '-o', 'json'))
        image = d['spec']['template']['spec']['containers'][0]['image']
        job = dict(apiVersion='batch/v1', kind='Job', metadata=dict(name='pfa-clear-metrics', namespace='pfa'),
                   spec=dict(backoffLimit=0, activeDeadlineSeconds=90, template=dict(
                       metadata=dict(labels={'app':'pfa-data-reset'}), spec=dict(restartPolicy='Never', automountServiceAccountToken=False,
                       securityContext=dict(runAsUser=65534, runAsNonRoot=True, fsGroup=65534, seccompProfile=dict(type='RuntimeDefault')),
                       containers=[dict(name='clear', image=image, command=['/bin/sh','-ec',
                           'test -d /prometheus; find /prometheus -mindepth 1 -maxdepth 1 ! -name lost+found -exec rm -rf -- {} +; test "$(find /prometheus -mindepth 1 -maxdepth 1 ! -name lost+found | wc -l)" -eq 0'],
                           securityContext=dict(allowPrivilegeEscalation=False, capabilities=dict(drop=['ALL'])),
                           resources=dict(limits=dict(cpu='100m', memory='64Mi')),
                           volumeMounts=[dict(name='data',mountPath='/prometheus')])],
                       volumes=[dict(name='data', persistentVolumeClaim=dict(claimName='prometheus-data'))]))))
        self.kube('pfa', 'apply', '-f', '-', stdin=json.dumps(job))
        self.kube('pfa', 'wait', 'job/pfa-clear-metrics', '--for=condition=complete', '--timeout=100s')
        self.kube('pfa', 'delete', 'job', 'pfa-clear-metrics', '--wait=true')

    def execute(self):
        before = self.inventory()
        Path(self.args.report).write_text(json.dumps({'before':before, 'status':'PREPARED'},indent=2))
        self.pause()
        queues = json.loads(self.kube('pfa', 'exec', 'rabbitmq-0', '--', 'rabbitmqctl', 'list_queues',
                                     'name', 'messages_unacknowledged', '--formatter=json'))
        if any(q['messages_unacknowledged'] for q in queues):
            raise RuntimeError('Unacknowledged jobs remain; refusing to erase receipt objects')
        for name in ('ocr-jobs', 'ocr-jobs-dead'):
            self.kube('pfa', 'exec', 'rabbitmq-0', '--', 'rabbitmqctl', 'purge_queue', name)
        self.sql('BEGIN; SET LOCAL lock_timeout=\'10s\'; TRUNCATE TABLE ' + ','.join(self.tables()) + ' RESTART IDENTITY; COMMIT;')
        self.sql('SELECT pg_stat_reset()')
        for bucket in ('receipts', 'profiles'):
            self.mc('clear', bucket)
        self.redis('FLUSHALL', 'SYNC')
        self.redis('SAVE')
        cutoff = datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
        self.kube('pfa-system', 'apply', '-f', '-', stdin=json.dumps(dict(apiVersion='v1',kind='ConfigMap',
                  metadata=dict(name='pfa-log-reset-checkpoint',namespace='pfa-system'),data=dict(cutoff=cutoff))))
        for table in before['clickhouse']:
            if not re.fullmatch(r'[a-z_][a-z_0-9]*',table['name']):
                raise RuntimeError('Unexpected ClickHouse table name')
            self.ch('TRUNCATE TABLE ' + table['database'] + '.' + table['name'] + ' SYNC')
        self.clear_prometheus()
        after = self.inventory()
        if any(after['postgres'].values()) or any(after['minio'].values()) or after['redis_keys']:
            raise RuntimeError('Application stores are not empty; workloads remain paused')
        if int(self.sql('SELECT count(*) FROM auth.users', replica=True)) or int(self.sql('SELECT count(*) FROM finance.transaction_entries', replica=True)):
            raise RuntimeError('Replica still has old rows; wait for replay before restoring')
        Path(self.args.report).write_text(json.dumps(dict(before=before, after=after, status='CLEARED', log_cutoff=cutoff),indent=2))
        self.restore()
        report = json.loads(Path(self.args.report).read_text())
        report['status'] = 'COMPLETE'
        Path(self.args.report).write_text(json.dumps(report,indent=2)+'\n')
        print('PFA application data cleared; configuration and read replica retained. Report:',self.args.report)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kubeconfig',default=str(Path.home()/'.kube/k3s.yaml'))
    p.add_argument('--report',default='/tmp/pfa-production-reset-report.json')
    p.add_argument('--state',default='/tmp/pfa-production-reset-state.json')
    p.add_argument('--execute',action='store_true')
    p.add_argument('--acknowledge-delete-all-pfa-data',action='store_true')
    p.add_argument('--restore-state',action='store_true')
    args=p.parse_args()
    reset=Reset(args)
    if args.restore_state:
        reset.restore()
    elif args.execute:
        if not args.acknowledge_delete_all_pfa_data:
            p.error('--execute requires --acknowledge-delete-all-pfa-data')
        reset.execute()
    else:
        print(json.dumps(reset.inventory(),indent=2))


if __name__=='__main__':
    main()
