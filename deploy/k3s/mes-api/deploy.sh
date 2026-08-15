#!/bin/bash
set -euo pipefail

# --- File Paths (Relative to the repo root) ---
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
API_DIR="${REPO_ROOT}/deploy/k3s/mes-api"
SECRET_MANIFEST="${API_DIR}/mes-api-db-secret.yaml"
GHCR_SECRET_MANIFEST="${API_DIR}/mes-api-ghcr-secret.yaml"
CONFIG_MANIFEST="${API_DIR}/mes-api-config.yaml"
DEPLOY_MANIFEST="${API_DIR}/deployment.yaml"
SERVICE_MANIFEST="${API_DIR}/service.yaml"
PDB_MANIFEST="${API_DIR}/pdb.yaml"
NAMESPACE_MANIFEST="${REPO_ROOT}/deploy/k3s/namespace.yaml"

IMAGE_REPO="ghcr.io/codeserengetiorganization/a-mes/mes-api"

# Pick package: explicit MES_API_IMAGE, else pom.xml version (SoT). Never default to :latest.
if [[ -n "${MES_API_IMAGE:-}" ]]; then
  IMAGE_REF="${MES_API_IMAGE}"
else
  PROJECT_VERSION="$(
    cd "${REPO_ROOT}/services/mes-api"
    mvn help:evaluate -Dexpression=project.version -q -DforceStdout
  )"
  IMAGE_REF="${IMAGE_REPO}:${PROJECT_VERSION}"
fi

echo "--- Starting mes-api Deployment (a-mes / K3s) ---"
echo "Image: ${IMAGE_REF}"

# 1. Apply Namespace — name from manifest (SoT), not a second hardcoded copy
echo "Step 1: Applying Namespace from ${NAMESPACE_MANIFEST}..."
TARGET_NAMESPACE="$(kubectl apply -f "${NAMESPACE_MANIFEST}" -o jsonpath='{.metadata.name}')"

# 2. Apply secrets (DB + GHCR pull)
if [[ ! -f "${SECRET_MANIFEST}" ]]; then
  echo "ERROR: ${SECRET_MANIFEST} not found."
  echo "       Copy from mes-api-db-secret.yaml.example"
  exit 1
fi
if [[ ! -f "${GHCR_SECRET_MANIFEST}" ]]; then
  echo "ERROR: ${GHCR_SECRET_MANIFEST} not found."
  echo "       Copy from mes-api-ghcr-secret.yaml.example"
  exit 1
fi
echo "Step 2: Applying Secret 'mes-api-db-secret' from ${SECRET_MANIFEST}..."
kubectl apply -f "${SECRET_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
echo "Step 2b: Applying Secret 'mes-api-ghcr-secret' from ${GHCR_SECRET_MANIFEST}..."
kubectl apply -f "${GHCR_SECRET_MANIFEST}" --namespace="${TARGET_NAMESPACE}"

# 3. Apply ConfigMap, Service, Deployment, PDB
echo "Step 3: Applying ConfigMap, Service, Deployment, and PDB..."
kubectl apply -f "${CONFIG_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
kubectl apply -f "${SERVICE_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
kubectl apply -f "${DEPLOY_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
kubectl apply -f "${PDB_MANIFEST}" --namespace="${TARGET_NAMESPACE}"

# 4. Pin the chosen package (overrides whatever image is in deployment.yaml)
echo "Step 4: Pinning image ${IMAGE_REF} on deployment/ames-mes-api..."
kubectl set image "deployment/ames-mes-api" "mes-api=${IMAGE_REF}" \
  --namespace="${TARGET_NAMESPACE}"

echo "Step 5: Waiting for ames-mes-api to become Ready..."
kubectl rollout status deployment/ames-mes-api --namespace="${TARGET_NAMESPACE}" --timeout=180s

echo "--- Deployment Complete! ---"
echo "Pinned image: ${IMAGE_REF}"
echo "In-cluster HTTP: http://ames-mes-api.${TARGET_NAMESPACE}.svc.cluster.local:8080"
echo "NodePort: http://<node-ip>:30100"
echo "Panel Registration join: POST /api/panel-registrations"
echo "Rollback: MES_API_IMAGE=${IMAGE_REPO}:<previous-version> $0"
