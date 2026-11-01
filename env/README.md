# Per-service environment

Each `<service>.env` file declares **which variables that service's Secret
gets**. `k8s/apply.sh` renders one Kubernetes Secret per file:

```
env/<service>.env  ->  Secret <service>-secrets  (namespace pfa)
```

This replaces the single shared `pfa-secrets` for the application layer. The
`pfa-secrets` Secret still exists because Postgres, Redis, MinIO, ClickHouse,
Grafana, Vector and the Cloudflare tunnel read it — do not remove it.

## Format

One entry per line. Blank lines and `#` comments are ignored.

| Line | Meaning |
|---|---|
| `DB_PASSWORD` | Copy the value of `DB_PASSWORD` from the repo-root `.env` |
| `RABBITMQ_PASSWORD=guest` | Literal override, independent of `.env` |

So `.env` stays the single source of truth for shared secrets, and each file
lists only the names that service consumes. Overriding a value for one service
is a one-line change here instead of editing the shared file.

## Overrides for real secrets

Values written as `NAME=value` land **in this file**, which is committed. Use
`.env` for anything sensitive. If you need a genuinely per-service secret, put
it in `env/<service>.local.env` — that file is gitignored and is merged last,
so it wins over both `.env` and this file:

```
printf 'DB_PASSWORD=only-command-service\n' > env/command-service.local.env
```

## Non-secret configuration

Everything that is not a credential lives in a committed ConfigMap per service
(`<service>-config`) inside `k8s/apps/<service>.yaml`. Hostnames, ports, JVM
flags and feature toggles are changed there, via git — not here.

## How the values reach the cluster

Secrets are committed **encrypted** in `k8s/sealed/` using
[Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets). The
controller (`kube-system`) decrypts them into normal Kubernetes Secrets.

```
.env + env/<service>.env
        │  ./k8s/seal-secrets.sh
        ▼
k8s/sealed/<name>.yaml        (safe to commit)
        │  git push → Argo CD (or kubectl apply)
        ▼
Sealed Secrets controller → Secret <name>
        │
        ▼
Reloader restarts any pod with:
        reloader.stakater.com/auto: "true"
```

## After changing any of this

```
./k8s/seal-secrets.sh --apply     # re-seal + apply, no cluster-wide restart
git add k8s/sealed/ && git commit -m 'chore(secrets): rotate …'
```

`sync-env.sh` does the same thing and additionally restarts workloads that
reference the Secrets, which covers the infrastructure pieces (Postgres, Redis,
Grafana, …) that are not yet annotated for Reloader.
