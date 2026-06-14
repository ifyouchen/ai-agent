#!/usr/bin/env bash
set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/ifyouchen/ai-agent.git}"
BRANCH="${BRANCH:-main}"
REMOTE_DIR="${REMOTE_DIR:-/opt/aiagent}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
FORCE_RESET="${FORCE_RESET:-false}"
FOLLOW_LOGS="${FOLLOW_LOGS:-false}"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[ERROR] Missing command: $1"
    exit 1
  fi
}

compose() {
  docker compose -f "$COMPOSE_FILE" "$@"
}

wait_for_app() {
  echo "[INFO] Waiting for ai-agent-app health check..."
  for _ in $(seq 1 60); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' ai-agent-app 2>/dev/null || true)"
    if [ "$status" = "healthy" ]; then
      echo "[OK] ai-agent-app is healthy"
      return 0
    fi
    if [ "$status" = "unhealthy" ]; then
      echo "[WARN] ai-agent-app is unhealthy, recent logs:"
      docker logs --tail=120 ai-agent-app || true
      return 1
    fi
    sleep 3
  done

  echo "[WARN] Timed out waiting for health check. Recent logs:"
  docker logs --tail=120 ai-agent-app || true
  return 1
}

need_cmd git
need_cmd docker

if ! docker compose version >/dev/null 2>&1; then
  echo "[ERROR] docker compose is not available"
  exit 1
fi

echo "[INFO] Deploy target: $REMOTE_DIR"
echo "[INFO] Repo: $REPO_URL"
echo "[INFO] Branch: $BRANCH"

mkdir -p "$(dirname "$REMOTE_DIR")"

if [ -d "$REMOTE_DIR/.git" ]; then
  echo "[INFO] Existing git repo found. Updating code..."
  cd "$REMOTE_DIR"

  if [ -f ".env" ]; then
    cp .env ".env.bak.$(date +%Y%m%d%H%M%S)"
    echo "[INFO] .env backup created"
  fi

  git fetch origin "$BRANCH"
  git checkout "$BRANCH"

  if [ "$FORCE_RESET" = "true" ]; then
    echo "[WARN] Force reset enabled. Local code changes will be overwritten."
    git reset --hard "origin/$BRANCH"
  else
    git pull --ff-only origin "$BRANCH"
  fi
else
  if [ -e "$REMOTE_DIR" ]; then
    backup="${REMOTE_DIR}_manual_backup_$(date +%Y%m%d%H%M%S)"
    echo "[INFO] Existing non-git directory found. Moving it to $backup"
    mv "$REMOTE_DIR" "$backup"
    git clone -b "$BRANCH" "$REPO_URL" "$REMOTE_DIR"
    if [ -f "$backup/.env" ]; then
      cp "$backup/.env" "$REMOTE_DIR/.env"
      echo "[INFO] Restored .env from old directory"
    fi
  else
    echo "[INFO] Cloning fresh repo..."
    git clone -b "$BRANCH" "$REPO_URL" "$REMOTE_DIR"
  fi
  cd "$REMOTE_DIR"
fi

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "[ERROR] Compose file not found: $REMOTE_DIR/$COMPOSE_FILE"
  exit 1
fi

if [ ! -f ".env" ]; then
  if [ -f ".env.example" ]; then
    cp .env.example .env
  fi
  echo "[ERROR] .env not found. Fill it first, then rerun deploy: nano $REMOTE_DIR/.env"
  exit 1
fi

if grep -Eq "请改成|填你的|your-" .env; then
  echo "[ERROR] .env still contains placeholder values. Edit it first: nano $REMOTE_DIR/.env"
  exit 1
fi

echo "[INFO] Building and starting containers..."
compose up -d --build

echo "[INFO] Container status:"
compose ps

wait_for_app || exit 1

echo "[INFO] Recent backend logs:"
docker logs --tail=120 ai-agent-app || true

echo "[OK] Deploy finished."

if [ "$FOLLOW_LOGS" = "true" ]; then
  echo "[INFO] Following backend logs. Press Ctrl+C to stop watching."
  docker logs -f --tail=200 ai-agent-app
fi
