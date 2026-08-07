# MySQL on K3s (dev) — StatefulSet + PVC

## Why StatefulSet + PVC

| Before | After |
|--------|--------|
| `Deployment` + `emptyDir` | `StatefulSet` `ames-mysql` + PVC `data-ames-mysql-0` |
| Pod delete → data gone | Pod delete → data kept on volume |

K3s storage class: `local-path` (default on most K3s installs).

## Credentials (single SoT)

All of `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` come from Secret **`mysql-secret`** (gitignored). No app password in git SQL.

The official MySQL image creates the database and app user on **first boot** when `/var/lib/mysql` is empty.

**Probes:** `startupProbe` / `readinessProbe` / `livenessProbe` use `mysqladmin ping`. The Service only sends traffic after readiness passes; startup allows a slow first init (~5 min) before liveness can restart the container.

## Files

| File | Role |
|------|------|
| `mysql-statefulset.yaml` | StatefulSet **`ames-mysql`** (MySQL 8.0.21 digest-pinned) + 5Gi PVC |
| `mysql-service.yaml` | ClusterIP Service **`ames-mysql`** (`serviceName` target) |
| `mysql-secret.yaml.example` | Copy → `mysql-secret.yaml` (gitignored) |
| `deploy.sh` | Apply namespace → secret → svc/sts |

## Deploy

```bash
cp deploy/k3s/mysql/mysql-secret.yaml.example deploy/k3s/mysql/mysql-secret.yaml
# edit values in mysql-secret.yaml
chmod +x deploy/k3s/mysql/deploy.sh
./deploy/k3s/mysql/deploy.sh
```

In-cluster host: `ames-mysql.ames-dev.svc.cluster.local`  
Database / user / password: see your local `mysql-secret.yaml` (gitignored). Example file uses `CHANGE_ME` for passwords — replace before first deploy.

## Persistence checks

```bash
# PVC should stay Bound after pod delete
kubectl delete pod ames-mysql-0 -n ames-dev
kubectl get pvc -n ames-dev
# ames-mysql-0 comes back; seeded data should still be there
```

To **wipe** and re-create DB/user from the Secret: delete the StatefulSet **and** PVC `data-ames-mysql-0`, then re-run `deploy.sh`.
