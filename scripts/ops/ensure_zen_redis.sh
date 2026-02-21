#!/usr/bin/env bash
set -euo pipefail

CONTAINER_NAME="${ZEN_REDIS_CONTAINER_NAME:-zen-mcp-redis}"
COMPOSE_FILE="${ZEN_REDIS_COMPOSE_FILE:-/home/danicous/trading2/zen-mcp-server/docker-compose.yml}"
WAIT_SECONDS="${ZEN_REDIS_WAIT_SECONDS:-30}"

if ! command -v docker >/dev/null 2>&1; then
  echo "[!] docker is not installed. Cannot start Redis for zen-mcp tooling." >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "[!] Compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

echo "[+] Ensuring Redis container '$CONTAINER_NAME' is running..."
docker compose -f "$COMPOSE_FILE" up -d redis >/dev/null

echo "[+] Waiting for Redis readiness (up to ${WAIT_SECONDS}s)..."
for _ in $(seq 1 "$WAIT_SECONDS"); do
  if docker exec "$CONTAINER_NAME" redis-cli ping >/dev/null 2>&1; then
    echo "[+] Redis is ready: PONG"
    docker ps --filter "name=$CONTAINER_NAME" --format "    {{.Names}} {{.Status}} {{.Ports}}"
    exit 0
  fi
  sleep 1
done

echo "[!] Redis container started but did not become ready in ${WAIT_SECONDS}s." >&2
docker logs --tail 40 "$CONTAINER_NAME" >&2 || true
exit 1
