"""Check gateway, credential and network contracts for storage inside Command."""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[3]


def load(path):
    return yaml.safe_load((ROOT / path).read_text())


def documents(path):
    return list(yaml.safe_load_all((ROOT / path).read_text()))


def app_container(service):
    deployment = next(doc for doc in documents(f'k8s/apps/{service}.yaml')
                      if doc['kind'] == 'Deployment')
    return deployment['spec']['template']['spec']['containers'][0]


class StorageConsolidationTests(unittest.TestCase):
    def test_public_storage_urls_keep_authentication_and_long_timeouts(self):
        config = load('edge-router/routes.yaml')['http']
        router = config['routers']['storage']
        self.assertEqual(router['middlewares'], ['sanitize', 'authenticate'])
        self.assertIn('/api/bill/', router['rule'])
        self.assertIn('/api/storage/', router['rule'])
        backend = config['services'][router['service']]['loadBalancer']
        self.assertEqual(backend['servers'], [{'url': 'http://command-service:8081'}])
        transport = config['serversTransports'][backend['serversTransport']]
        self.assertEqual(transport['forwardingTimeouts']['responseHeaderTimeout'], '120s')
        self.assertEqual(config['middlewares']['sanitize']['headers']
                         ['customRequestHeaders']['X-User-Id'], '')
        self.assertEqual(config['middlewares']['authenticate']['forwardAuth']
                         ['address'], 'http://auth-service:8082/internal/verify')

    def test_local_rate_exemption_is_loopback_only_and_cannot_be_claimed_publicly(self):
        static = load('edge-router/traefik.yaml')
        self.assertEqual(static['entryPoints']['local-test']['address'], '127.0.0.1:8085')
        config = load('edge-router/routes.yaml')['http']
        self.assertEqual(config['middlewares']['sanitize']['headers']
                         ['customRequestHeaders']['X-PFA-Local-Test'], '')
        marker = config['middlewares']['local-test-marker']['headers']['customRequestHeaders']
        self.assertEqual(marker, {'X-PFA-Local-Test': '1'})
        for name, router in config['routers'].items():
            if name.endswith('-local-test'):
                public = config['routers'][name.removesuffix('-local-test')]
                self.assertEqual(router['entryPoints'], ['local-test'])
                self.assertEqual(router['middlewares'][:3],
                                 ['sanitize', 'local-test-marker', 'authenticate'])
                self.assertEqual(router['rule'], public['rule'])
                self.assertEqual(router['service'], public['service'])
            else:
                self.assertEqual(router['entryPoints'], ['web'])
                self.assertNotIn('local-test-marker', router['middlewares'])
        service = next(doc for doc in documents('k8s/apps/edge-router.yaml')
                       if doc['kind'] == 'Service')
        self.assertNotIn(8085, [port['port'] for port in service['spec']['ports']])

    def test_storage_credentials_are_selective_and_auth_stays_separate(self):
        container = app_container('command-service')
        selected = {item['name']: item['valueFrom']['secretKeyRef']
                    for item in container['env'] if 'valueFrom' in item}
        self.assertEqual(set(selected), {
            'MINIO_ACCESS_KEY', 'MINIO_ROOT_PASSWORD', 'RABBITMQ_USER', 'RABBITMQ_PASSWORD'})
        bundle = load('k8s/sealed/storage-service-secrets.yaml')['spec']['encryptedData']
        for key, reference in selected.items():
            self.assertEqual(reference, {'name': 'storage-service-secrets', 'key': key})
            self.assertIn(key, bundle)
        imported = [item['secretRef']['name'] for item in container['envFrom']
                    if 'secretRef' in item]
        self.assertNotIn('storage-service-secrets', imported)
        auth = app_container('auth-service')
        self.assertNotIn('storage-service-secrets', str(auth['envFrom']))

    def test_network_policies_allow_both_directions_without_broadening_auth(self):
        policies = {doc['metadata']['name']: doc['spec']
                    for doc in documents('k8s/apps/network-policies.yaml')}

        def allows(policy, direction, peer, port):
            peers_key = 'to' if direction == 'egress' else 'from'
            return any(
                any(item.get('podSelector', {}).get('matchLabels', {}).get('app') == peer
                    for item in rule.get(peers_key, []))
                and any(item['port'] == port for item in rule.get('ports', []))
                for rule in policies[policy].get(direction, []))

        for backend, port in [('minio', 9000), ('rabbitmq', 5672)]:
            self.assertTrue(allows('command-service-ingress', 'egress', backend, port))
            self.assertTrue(allows(f'{backend}-ingress', 'ingress', 'command-service', port))
            self.assertFalse(allows('auth-service-ingress', 'egress', backend, port))
        self.assertTrue(allows('edge-router-ingress', 'egress', 'command-service', 8081))
        self.assertTrue(allows('command-service-ingress', 'ingress', 'edge-router', 8081))
        self.assertEqual(policies['default-deny-egress']['egress'], [])
        self.assertNotIn('storage-service-ingress', policies)

    def test_compose_supplies_the_combined_rust_services_dependencies(self):
        services = load('docker-compose.yml')['services']
        self.assertNotIn('storage-service', services)
        command = services['command-service']
        self.assertTrue({'postgres-primary', 'redis', 'rabbitmq', 'minio'}
                        <= set(command['depends_on']))
        self.assertTrue({'DB_USER', 'DB_PASSWORD', 'DB_HOST', 'MINIO_SECRET_KEY',
                         'RABBITMQ_USER', 'RABBITMQ_PASSWORD'} <= set(command['environment']))
        self.assertFalse(any(key.startswith('SPRING_') for key in command['environment']))
        self.assertFalse((ROOT / 'k8s/apps/storage-service.yaml').exists())

    def test_metrics_and_dashboard_sources_agree(self):
        prometheus = load('observability/prometheus/prometheus.yml')
        rendered = yaml.safe_load(load('k8s/config/infra/prometheus-config.yaml')
                                  ['data']['prometheus.yml'])
        local_jobs = {job['job_name']: job for job in prometheus['scrape_configs']}
        cluster_jobs = {job['job_name']: job for job in rendered['scrape_configs']}
        # Vector's DNS name and kubelet discovery intentionally differ in
        # Compose and Kubernetes; compare the application scrape contracts.
        for name in ['auth-service', 'command-service', 'query-service', 'edge-router']:
            self.assertEqual(local_jobs[name], cluster_jobs[name])
        self.assertNotIn('storage-service', local_jobs)
        self.assertNotIn('storage-service', cluster_jobs)
        dashboards = load('k8s/config/infra/grafana-dashboards.yaml')['data']
        for name, contents in dashboards.items():
            source = ROOT / 'observability/grafana/dashboards' / name
            self.assertEqual(contents.strip(), source.read_text().strip(), name)

    def test_image_builds_include_the_separate_test_crates(self):
        for service in ['auth-service', 'command-service', 'query-service']:
            dockerfile = (ROOT / service / 'Dockerfile').read_text()
            self.assertIn('COPY tests ./tests', dockerfile)
            self.assertLess(dockerfile.index('COPY tests ./tests'), dockerfile.index('cargo test'))
            self.assertTrue(list((ROOT / service / 'tests').glob('*.rs')))


if __name__ == '__main__':
    unittest.main()
