# station-gate-ui on K3s (dev) — Deployment + NodePort 30102

Promote the AS3-03 Station Gate demo screen onto the same cluster as mes-api. Demo only — not a floor station screen.

API URL is **baked into the image** at CI build time: `VITE_MES_API_BASE_URL=http://localhost:30100`.

**Open the UI on the K3s laptop itself** at `http://localhost:30102`. The laptop's IP changes with every Wi-Fi network (DHCP), so this app uses `localhost` instead of a node IP. A browser on another machine would load the page but its MES calls would go to *that* machine's localhost and fail.

## CI (GitHub Actions → GHCR)

Workflow: [`.github/workflows/station-gate-ui-ci-cd.yml`](../../../.github/workflows/station-gate-ui-ci-cd.yml)

**Image SoT:** `apps/station-gate-ui/package.json` version → GHCR tag `$VERSION`.

| Trigger | What happens |
|---------|----------------|
| PR to `develop` (UI paths) | lint → test → image build → push **`$VERSION` only** |
| Push to `develop` (UI paths) | lint → test → image build → push **`$VERSION` + `:latest`** |

**CD:** Actions does **not** talk to K3s. Promote with `deploy.sh`.

The same `$VERSION` tag is re-pushed on every build. When the tag on the cluster already matches, `deploy.sh` runs `kubectl rollout restart` so the pods pull what GHCR has now (`imagePullPolicy: Always`).

## Files

| File | Role |
|------|------|
| [`apps/station-gate-ui/Dockerfile`](../../../apps/station-gate-ui/Dockerfile) | **Only** image build SoT (CI + local) |
| `deployment.yaml` | Deployment **`ames-station-gate-ui`** — image placeholder; real tag via `deploy.sh` |
| `pdb.yaml` | PodDisruptionBudget — `minAvailable: 1` |
| `service.yaml` | NodePort Service **`ames-station-gate-ui`** (`:30102`) |
| `deploy.sh` | apply → **pin image** → restart if tag unchanged → rollout |

GHCR pull: reuse **`mes-api-ghcr-secret`** only (same org registry — no UI-local secret).

## Deploy (pick package → K3s)

Prereqs:

1. mes-api on **30100** includes AS3-04 — `POST /api/station-quality-results` answers `400` to `{}`, not `404`.
2. CORS allows the UI origin. `deploy/k3s/mes-api/mes-api-config.yaml` lists `http://localhost:30102` and `http://127.0.0.1:30102`; after changing it, apply the ConfigMap and restart mes-api (env is read at pod start):

   ```bash
   kubectl apply -f deploy/k3s/mes-api/mes-api-config.yaml
   kubectl -n ames-dev rollout restart deploy/ames-mes-api
   kubectl -n ames-dev rollout status deploy/ames-mes-api
   ```

3. A `station-gate-ui` image on GHCR — built by CI from a PR to `develop`.

```bash
chmod +x deploy/k3s/station-gate-ui/deploy.sh
./deploy/k3s/station-gate-ui/deploy.sh

# Explicit package / named rollback
STATION_GATE_UI_IMAGE=ghcr.io/codeserengetiorganization/a-mes/station-gate-ui:0.0.1 \
  ./deploy/k3s/station-gate-ui/deploy.sh
```

NodePort: `http://localhost:30102` (A-MES block — see [`../README.md`](../README.md))

## Smoke (AS3-03 on K3s)

1. Open `http://localhost:30102` on the K3s laptop — Station Gate screen loads.
2. **Deny** — Assembly Station + a fresh demo serial → Denied, expected next Coating (COAT).
3. **Allow + COMPLETE** — Conformal Coating Machine → Allowed → COMPLETE recorded, expected next UV Inspection (UV).
4. **No CORS failure** — the result panel never shows "Cannot reach MES" while mes-api is up.

Browser Network tab should call `http://localhost:30100/api/station-gate-checks` (baked base URL).

Writes go to the K3s dev MySQL; a serial walked to Packing stays done. Register fresh panels with panel-registration-ui (30101) for repeat demos.
