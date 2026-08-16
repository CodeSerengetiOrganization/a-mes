#!/bin/bash
set -euo pipefail

# --- File Paths (Relative to the repo root) ---
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
UI_DIR="${REPO_ROOT}/deploy/k3s/panel-registration-ui"
MES_API_DIR="${REPO_ROOT}/deploy/k3s/mes-api"
GHCR_SECRET_MANIFEST="${MES_API_DIR}/mes-api-ghcr-secret.yaml"
DEPLOY_MANIFEST="${UI_DIR}/deployment.yaml"
SERVICE_MANIFEST="${UI_DIR}/service.yaml"
PDB_MANIFEST="${UI_DIR}/pdb.yaml"
NAMESPACE_MANIFEST="${REPO_ROOT}/deploy/k3s/namespace.yaml"

IMAGE_REPO="ghcr.io/codeserengetiorganization/a-mes/panel-registration-ui"

# Pick package: explicit PANEL_REGISTRATION_UI_IMAGE, else package.json version (SoT). Never default to :latest.
if [[ -n "${PANEL_REGISTRATION_UI_IMAGE:-}" ]]; then
  IMAGE_REF="${PANEL_REGISTRATION_UI_IMAGE}"
else
  PROJECT_VERSION="$(
    node -p "require('${REPO_ROOT}/apps/panel-registration-ui/package.json').version"
  )"
  IMAGE_REF="${IMAGE_REPO}:${PROJECT_VERSION}"
fi

echo "--- Starting panel-registration-ui Deployment (a-mes / K3s) ---"
echo "Image: ${IMAGE_REF}"

# 1. Apply Namespace
echo "Step 1: Applying Namespace from ${NAMESPACE_MANIFEST}..."
TARGET_NAMESPACE="$(kubectl apply -f "${NAMESPACE_MANIFEST}" -o jsonpath='{.metadata.name}')"

# 2. One GHCR pull secret for ames-dev (shared with mes-api)
if [[ ! -f "${GHCR_SECRET_MANIFEST}" ]]; then
  echo "ERROR: ${GHCR_SECRET_MANIFEST} not found."
  echo "       Copy from deploy/k3s/mes-api/mes-api-ghcr-secret.yaml.example"
  exit 1
fi
echo "Step 2: Applying Secret 'mes-api-ghcr-secret' from ${GHCR_SECRET_MANIFEST}..."
kubectl apply -f "${GHCR_SECRET_MANIFEST}" --namespace="${TARGET_NAMESPACE}"

# 3. Apply Service, Deployment, PDB
echo "Step 3: Applying Service, Deployment, and PDB..."
kubectl apply -f "${SERVICE_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
kubectl apply -f "${DEPLOY_MANIFEST}" --namespace="${TARGET_NAMESPACE}"
kubectl apply -f "${PDB_MANIFEST}" --namespace="${TARGET_NAMESPACE}"

# 4. Pin the chosen package
echo "Step 4: Pinning image ${IMAGE_REF} on deployment/ames-panel-registration-ui..."
kubectl set image "deployment/ames-panel-registration-ui" "panel-registration-ui=${IMAGE_REF}" \
  --namespace="${TARGET_NAMESPACE}"

echo "Step 5: Waiting for ames-panel-registration-ui to become Ready..."
kubectl rollout status deployment/ames-panel-registration-ui --namespace="${TARGET_NAMESPACE}" --timeout=120s

echo "--- Deployment Complete! ---"
echo "Pinned image: ${IMAGE_REF}"
echo "NodePort UI:  http://<node-ip>:30101"
echo "UI calls API: http://<node-ip>:30100  (baked into image via CI VITE_MES_API_BASE_URL)"
echo "Smoke: open UI → success register + clear deny (see README)"
echo "Rollback: PANEL_REGISTRATION_UI_IMAGE=${IMAGE_REPO}:<previous-version> $0"
