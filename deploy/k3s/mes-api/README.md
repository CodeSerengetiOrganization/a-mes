# mes-api on K3s (dev) — Deployment + NodePort

Promote the Spring Boot MES API (Panel Registration join) onto the same cluster as MySQL.

MySQL is already on K3s (`ames-mysql` StatefulSet). This folder only deploys the API.

## CI (GitHub Actions → GHCR)

Workflow: [`.github/workflows/mes-api-ci-cd.yml`](../../../.github/workflows/mes-api-ci-cd.yml)

**Image SoT:** `services/mes-api/pom.xml` version → GHCR tag `$PROJECT_VERSION`.

| Trigger | What happens |
|---------|----------------|
| PR to `develop` | `mvn clean verify` → push **`$VERSION` only** (does not move `:latest`) |
| Push to `develop` | `mvn clean verify` → push **`$VERSION` + `:latest`** |

**CD:** Actions does **not** talk to K3s. Promote with `deploy.sh` (pins a version tag — never relies on `:latest` for staging).

Default in `deployment.yaml`: **`…/mes-api:USE_DEPLOY_DOT_SH_ONLY`** (placeholder — not a real tag). **Always** promote with `deploy.sh`; image SoT is `pom.xml` (or `MES_API_IMAGE`).

## Files

| File | Role |
|------|------|
| [`services/mes-api/Dockerfile`](../../../services/mes-api/Dockerfile) | **Only** image build SoT (CI + local) |
| `deployment.yaml` | Deployment **`ames-mes-api`** — image placeholder; real tag via `deploy.sh` |
| `pdb.yaml` | PodDisruptionBudget **`ames-mes-api`** — `minAvailable: 1` |
| `service.yaml` | NodePort Service **`ames-mes-api`** (`:30100`) |
| `mes-api-config.yaml` | JDBC URL + CORS origins (UI NodePort **30101**; no Spring profile) |
| `mes-api-db-secret.yaml.example` | Copy → `mes-api-db-secret.yaml` (gitignored) |
| `mes-api-ghcr-secret.yaml.example` | Copy → `mes-api-ghcr-secret.yaml` (gitignored) — GHCR image pull |
| `deploy.sh` | apply → **pin image** (`kubectl set image`) → rollout |

## Deploy (pick package → K3s)

```bash
# 1. Secrets (once) — gitignored, do not commit
cp deploy/k3s/mes-api/mes-api-db-secret.yaml.example deploy/k3s/mes-api/mes-api-db-secret.yaml
# set SPRING_DATASOURCE_PASSWORD to the same value as mysql-secret
# mes-api-ghcr-secret.yaml: paste the GHCR dockerconfigjson (ames-dev namespace)

# 2. Promote — pins pom version from services/mes-api/pom.xml
chmod +x deploy/k3s/mes-api/deploy.sh
./deploy/k3s/mes-api/deploy.sh

# Explicit package / named rollback
MES_API_IMAGE=ghcr.io/codeserengetiorganization/a-mes/mes-api:0.0.1-SNAPSHOT \
  ./deploy/k3s/mes-api/deploy.sh
```

Local jar + image (laptop only — prefer GHCR for staging):

```bash
cd services/mes-api
mvn clean verify
docker build -t mes-api:0.0.1-SNAPSHOT .
```

In-cluster host: `ames-mes-api.ames-dev.svc.cluster.local:8080`  
NodePort: `http://<node-ip>:30100` (A-MES block — see [`../README.md`](../README.md))

## Smoke (ticket AC)

```bash
# success join — work order must already have a process path
curl -sS -X POST http://<node-ip>:30100/api/panel-registrations \
  -H 'Content-Type: application/json' \
  -d '{"panelNumber":"PANEL-STAGING-001","workOrderId":"WO-DEMO-001"}'

# deny (duplicate panel)
curl -sS -X POST http://<node-ip>:30100/api/panel-registrations \
  -H 'Content-Type: application/json' \
  -d '{"panelNumber":"PANEL-STAGING-001","workOrderId":"WO-DEMO-001"}'
```

Flyway runs when the API starts, so pending AS-2 schema rides this image.
