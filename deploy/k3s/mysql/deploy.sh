#!/bin/bash
set -euo pipefail

# --- File Paths (Relative to the repo root) ---
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DB_DIR="${REPO_ROOT}/deploy/k3s/mysql"
SECRET_MANIFEST="${DB_DIR}/mysql-secret.yaml"
DB_MANIFEST="${DB_DIR}/mysql-statefulset.yaml"
SERVICE_MANIFEST="${DB_DIR}/mysql-service.yaml"
NAMESPACE_MANIFEST="${REPO_ROOT}/deploy/k3s/namespace.yaml"

TARGET_NAMESPACE="ames-dev"

echo "--- Starting Database Infrastructure Deployment (a-mes / K3s) ---"

# 1. Apply Namespace
echo "Step 1: Applying Namespace from ${NAMESPACE_MANIFEST}..."
kubectl apply -f "${NAMESPACE_MANIFEST}"

# 2. Apply the Secret (DB name / user / passwords — single SoT)
if [[ ! -f "${SECRET_MANIFEST}" ]]; then
  echo "ERROR: ${SECRET_MANIFEST} not found."
  echo "       Copy from mysql-secret.yaml.example"
  exit 1
fi
echo "Step 2: Applying Secret 'mysql-secret' from ${SECRET_MANIFEST}..."
kubectl apply -f "${SECRET_MANIFEST}" --namespace="${TARGET_NAMESPACE}"

# 3. Remove legacy names if present (Deployment/StatefulSet/Service named mysql)
if kubectl get deployment mysql --namespace="${TARGET_NAMESPACE}" >/dev/null 2>&1; then
  echo "Step 3a: Removing old Deployment 'mysql'..."
  kubectl delete deployment mysql --namespace="${TARGET_NAMESPACE}" --wait=true
fi
if kubectl get statefulset mysql --namespace="${TARGET_NAMESPACE}" >/dev/null 2>&1; then
  echo "Step 3b: Removing old StatefulSet 'mysql' (renamed to ames-mysql)..."
  kubectl delete statefulset mysql --namespace="${TARGET_NAMESPACE}" --wait=true
fi
if kubectl get service mysql --namespace="${TARGET_NAMESPACE}" >/dev/null 2>&1; then
  echo "Step 3c: Removing old Service 'mysql' (renamed to ames-mysql)..."
  kubectl delete service mysql --namespace="${TARGET_NAMESPACE}"
fi

# 4. Apply Service first (StatefulSet.serviceName → ames-mysql), then StatefulSet
echo "Step 4: Applying MySQL Service and StatefulSet..."
kubectl apply -f "${SERVICE_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
kubectl apply -f "${DB_MANIFEST}" --namespace="${TARGET_NAMESPACE}"

echo "Step 5: Waiting for ames-mysql-0 to become Ready..."
kubectl rollout status statefulset/ames-mysql --namespace="${TARGET_NAMESPACE}" --timeout=180s

echo "--- Deployment Complete! ---"
echo "In-cluster host: ames-mysql.${TARGET_NAMESPACE}.svc.cluster.local"
echo "PVC (survives pod delete): data-ames-mysql-0"
echo "DB/user/password: from Secret mysql-secret (MYSQL_* env; first empty datadir only)."
