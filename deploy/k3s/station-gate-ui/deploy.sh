#!/bin/bash
set -euo pipefail

# --- File Paths (Relative to the repo root) ---
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
UI_DIR="${REPO_ROOT}/deploy/k3s/station-gate-ui"
MES_API_DIR="${REPO_ROOT}/deploy/k3s/mes-api"
GHCR_SECRET_MANIFEST="${MES_API_DIR}/mes-api-ghcr-secret.yaml"
DEPLOY_MANIFEST="${UI_DIR}/deployment.yaml"
SERVICE_MANIFEST="${UI_DIR}/service.yaml"
PDB_MANIFEST="${UI_DIR}/pdb.yaml"
NAMESPACE_MANIFEST="${REPO_ROOT}/deploy/k3s/namespace.yaml"

IMAGE_REPO="ghcr.io/codeserengetiorganization/a-mes/station-gate-ui"

# Pick package: explicit STATION_GATE_UI_IMAGE, else package.json version (SoT). Never default to :latest.
if [[ -n "${STATION_GATE_UI_IMAGE:-}" ]]; then
  IMAGE_REF="${STATION_GATE_UI_IMAGE}"
else
  PROJECT_VERSION="$(
    node -p "require('${REPO_ROOT}/apps/station-gate-ui/package.json').version"
  )"
  IMAGE_REF="${IMAGE_REPO}:${PROJECT_VERSION}"
fi

echo "--- Starting station-gate-ui Deployment (a-mes / K3s) ---"
echo "Image: ${IMAGE_REF}"

# 1. Apply Namespace
echo "Step 1: Applying Namespace from ${NAMESPACE_MANIFEST}..."
TARGET_NAMESPACE="$(kubectl apply -f "${NAMESPACE_MANIFEST}" -o jsonpath='{.metadata.name}')"

# Read before step 3: applying deployment.yaml briefly resets the image to the placeholder.
PREVIOUS_IMAGE="$(kubectl get deployment/ames-station-gate-ui --namespace="${TARGET_NAMESPACE}" \
  -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"

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
echo "Step 4: Pinning image ${IMAGE_REF} on deployment/ames-station-gate-ui..."
kubectl set image "deployment/ames-station-gate-ui" "station-gate-ui=${IMAGE_REF}" \
  --namespace="${TARGET_NAMESPACE}"

# Same tag as the running pods → the pod spec did not change and no new pods start.
# CI re-pushes the same version tag on every build, so restart to pull what GHCR has now.
if [[ "${PREVIOUS_IMAGE}" == "${IMAGE_REF}" ]]; then
  echo "Step 4b: Tag unchanged — restarting pods to pull the current ${IMAGE_REF}..."
  kubectl rollout restart "deployment/ames-station-gate-ui" --namespace="${TARGET_NAMESPACE}"
fi

echo "Step 5: Waiting for ames-station-gate-ui to become Ready..."
kubectl rollout status deployment/ames-station-gate-ui --namespace="${TARGET_NAMESPACE}" --timeout=120s

echo "--- Deployment Complete! ---"
echo "Pinned image: ${IMAGE_REF}"
echo "NodePort UI:  http://localhost:30102  (open on the K3s laptop)"
echo "UI calls API: http://localhost:30100  (baked into image via CI VITE_MES_API_BASE_URL)"
echo "Smoke: open UI → gate check deny + allow + COMPLETE (see README)"
echo "Rollback: STATION_GATE_UI_IMAGE=${IMAGE_REPO}:<previous-version> $0"
