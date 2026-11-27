#!/usr/bin/env python3
"""Exercise registry-maintenance admission boundaries without changing resources."""
import argparse
import json
import os
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', default=os.environ.get('KUBECONFIG'))
    args = parser.parse_args()
    base = ['kubectl'] + (['--kubeconfig', args.kubeconfig] if args.kubeconfig else [])
    base += ['-n', 'pfa']
    identity = ['--as=system:serviceaccount:pfa:registry-gc']
    results = []
    def check(name, command, allowed):
        p = subprocess.run(base + identity + command, capture_output=True, text=True,
                           env={**os.environ, 'KUBECTL_REMOTE_COMMAND_WEBSOCKETS': 'false'}, timeout=90)
        denied = 'pfa-registry-gc-' in p.stderr and ('denied' in p.stderr or 'failed' in p.stderr)
        ok = (p.returncode == 0 or (name == 'runner_activity' and 'exit code 1' in p.stderr)) if allowed else denied
        results.append({'check': name, 'allowed': allowed, 'passed': ok})
        if not ok:
            # Commands contain no credentials; errors are limited to admission messages.
            raise RuntimeError(name + ': ' + p.stderr[:500])
    check('read_only_toggle', ['patch', 'deployment', 'registry', '--type=strategic', '--dry-run=server', '-p',
        json.dumps({'spec': {'template': {'spec': {'containers': [{'name':'registry','env':[{'name':'REGISTRY_STORAGE_MAINTENANCE_READONLY','value':'{enabled: true}'}]}]}}}})], True)
    attacks = {
        'mount_database_secret': {'volumes':[{'name':'database-secret','secret':{'secretName':'postgres-secrets'}}]},
        'replace_service_account': {'serviceAccountName':'prometheus'},
        'enable_host_network': {'hostNetwork':True},
        'inject_environment_secret': {'containers':[{'name':'registry','envFrom':[{'secretRef':{'name':'postgres-secrets'}}]}]},
        'replace_image': {'containers':[{'name':'registry','image':'busybox:1.36'}]},
        'replace_command': {'containers':[{'name':'registry','command':['sh','-c','true']}]},
        'replace_probe_command': {'containers':[{'name':'registry','livenessProbe':{'$patch':'replace','exec':{'command':['sh','-c','true']}}}]},
        'enable_privileged': {'containers':[{'name':'registry','securityContext':{'privileged':True,'allowPrivilegeEscalation':True}}]},
        'inject_read_only_secret': {'containers':[{'name':'registry','env':[{'name':'REGISTRY_STORAGE_MAINTENANCE_READONLY','value':None,'valueFrom':{'secretKeyRef':{'name':'postgres-secrets','key':'DB_PASSWORD'}}}]}]},
    }
    for name, spec in attacks.items():
        check(name, ['patch','deployment','registry','--type=strategic','--dry-run=server','-p',json.dumps({'spec':{'template':{'spec':spec}}})], False)
    pods = json.loads(subprocess.check_output(base + ['get','pods','-o','json']))['items']
    by_app = {p['metadata'].get('labels',{}).get('app'): p['metadata']['name'] for p in pods if p['status'].get('phase')=='Running'}
    check('runner_activity', ['exec', by_app['ci-runner'], '-c', 'runner', '--', 'pgrep','-f','Runner.Worker'], True)
    check('database_exec', ['exec', by_app['postgres-primary'], '--', 'true'], False)
    check('arbitrary_registry_exec', ['exec', by_app['registry'], '--', 'true'], False)
    check('registry_read_only_gc', ['exec', by_app['registry'], '--', 'registry','garbage-collect','--dry-run','--delete-untagged','/etc/registry/config.yml'], True)
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
