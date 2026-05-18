#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_NAME="${MACHIKORO_SMOKE_PROJECT:-machikoro-recovery-smoke}"
PUBLIC_PORT="${PUBLIC_PORT:-58080}"
SMOKE_DB_PORT="${SMOKE_DB_PORT:-55432}"
ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/machikoro-smoke-env.XXXXXX")"

compose() {
  docker compose \
    -p "${PROJECT_NAME}" \
    -f "${ROOT_DIR}/compose.yaml" \
    -f "${ROOT_DIR}/compose.smoke-test.yaml" \
    --env-file "${ENV_FILE}" \
    "$@"
}

cleanup() {
  local exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    echo
    echo "Smoke test failed; backend logs follow:"
    compose logs backend || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "${ENV_FILE}"
  exit "${exit_code}"
}
trap cleanup EXIT

cat > "${ENV_FILE}" <<ENV
DB_NAME=machikoro_smoke
DB_USERNAME=smoke
DB_PASSWORD=smoke
PUBLIC_PORT=${PUBLIC_PORT}
SMOKE_DB_PORT=${SMOKE_DB_PORT}
WEBSOCKET_ALLOWED_ORIGINS=http://localhost:${PUBLIC_PORT}
IMAGE_TAG=smoke
SMOKE_POSTGRES_CONTAINER=${PROJECT_NAME}-db
SMOKE_BACKEND_CONTAINER=${PROJECT_NAME}-server
ENV

echo "Starting smoke-test stack on http://localhost:${PUBLIC_PORT}"
compose up -d --build postgres backend

for _ in {1..60}; do
  if curl -fsS "http://localhost:${PUBLIC_PORT}/actuator/health" >/dev/null; then
    break
  fi
  sleep 2
done

curl -fsS "http://localhost:${PUBLIC_PORT}/actuator/health" >/dev/null

cd "${ROOT_DIR}"
MACHIKORO_DOCKER_SMOKE=true \
MACHIKORO_SMOKE_BASE_URL="http://localhost:${PUBLIC_PORT}" \
MACHIKORO_SMOKE_WS_URL="ws://localhost:${PUBLIC_PORT}/ws" \
MACHIKORO_SMOKE_DB_PORT="${SMOKE_DB_PORT}" \
MACHIKORO_SMOKE_DB_NAME="machikoro_smoke" \
MACHIKORO_SMOKE_DB_USERNAME="smoke" \
MACHIKORO_SMOKE_DB_PASSWORD="smoke" \
MACHIKORO_SMOKE_COMPOSE_PROJECT="${PROJECT_NAME}" \
MACHIKORO_SMOKE_ENV_FILE="${ENV_FILE}" \
MACHIKORO_SMOKE_ROOT="${ROOT_DIR}" \
  ./gradlew test --tests org.machikoro.server.service.BackendContainerRestartRecoverySmokeTest
