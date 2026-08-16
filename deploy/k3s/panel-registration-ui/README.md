# panel-registration-ui on K3s (dev) — Deployment + NodePort 30101

Promote the PCB Loader Panel Registration UI onto the same cluster as mes-api.

API URL is **baked into the image** at CI build time (`VITE_MES_API_BASE_URL` → NodePort **30100**). This folder does not set a runtime API ConfigMap (see Epic A unsolved Q7 for future nginx proxy).

## CI (GitHub Actions → GHCR)

Workflow: [`.github/workflows/panel-registration-ui-ci-cd.yml`](../../../.github/workflows/panel-registration-ui-ci-cd.yml)

**Image SoT:** `apps/panel-registration-ui/package.json` version → GHCR tag `$VERSION`.

| Trigger | What happens |
|---------|----------------|
| PR to `develop` (UI paths) | lint → image build → push **`$VERSION` only** |
| Push to `develop` (UI paths) | lint → image build → push **`$VERSION` + `:latest`** |

**CD:** Actions does **not** talk to K3s. Promote with `deploy.sh` (pins a version tag — never relies on `:latest` for staging).

Default in `deployment.yaml`: **`…/panel-registration-ui:USE_DEPLOY_DOT_SH_ONLY`**. **Always** promote with `deploy.sh`.

## Files

| File | Role |
|------|------|
| [`apps/panel-registration-ui/Dockerfile`](../../../apps/panel-registration-ui/Dockerfile) | **Only** image build SoT (CI + local) |
| `deployment.yaml` | Deployment **`ames-panel-registration-ui`** — image placeholder; real tag via `deploy.sh` |
| `pdb.yaml` | PodDisruptionBudget — `minAvailable: 1` |
| `service.yaml` | NodePort Service **`ames-panel-registration-ui`** (`:30101`) |
| `deploy.sh` | apply → **pin image** (`kubectl set image`) → rollout |

GHCR pull: reuse **`mes-api-ghcr-secret`** only (same org registry — no UI-local secret).

## Deploy (pick package → K3s)

Prereq: **AS2-deploy-api** smoke-green (mes-api on **30100**). CORS ConfigMap must allow UI origin `http://<node-ip>:30101`.

```bash
# 1. GHCR pull secret (once) — same file as mes-api (gitignored)
#    deploy/k3s/mes-api/mes-api-ghcr-secret.yaml
#    (copy from mes-api-ghcr-secret.yaml.example if missing)

# 2. Promote — pins version from apps/panel-registration-ui/package.json
chmod +x deploy/k3s/panel-registration-ui/deploy.sh
./deploy/k3s/panel-registration-ui/deploy.sh

# Explicit package / named rollback
PANEL_REGISTRATION_UI_IMAGE=ghcr.io/codeserengetiorganization/a-mes/panel-registration-ui:0.0.1 \
  ./deploy/k3s/panel-registration-ui/deploy.sh
```

NodePort: `http://<node-ip>:30101` (A-MES block — see [`../README.md`](../README.md))

## Smoke (AS2-deploy-ui)

1. Open `http://<node-ip>:30101` — Panel Registration / PCB Loader screen loads.
2. **Success** register — panel joins WO that already has a process path (same as API smoke).
3. **Deny** — duplicate panel or unknown WO; UI shows a clear reason (not a blank CORS failure).

Browser Network tab should call `http://<node-ip>:30100/api/panel-registrations` (baked base URL).
