#!/usr/bin/env bash
set -Eeuo pipefail

# ── Default parameter values ──────────────────────────────────────────────────
HOST_NAME="${HOST_NAME:-43.155.132.161}"
USER_NAME="${USER_NAME:-}"
PORT="${PORT:-22}"
REPO_URL="${REPO_URL:-https://github.com/ifyouchen/ai-agent.git}"
BRANCH="${BRANCH:-main}"
REMOTE_DIR="${REMOTE_DIR:-/opt/aiagent}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.yml}"
FORCE_RESET="${FORCE_RESET:-false}"
FOLLOW_LOGS="${FOLLOW_LOGS:-false}"

# ── Parse named arguments ──────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host|-H)       HOST_NAME="$2";  shift 2 ;;
    --user|-u)       USER_NAME="$2";  shift 2 ;;
    --port|-p)       PORT="$2";       shift 2 ;;
    --repo)          REPO_URL="$2";   shift 2 ;;
    --branch|-b)     BRANCH="$2";     shift 2 ;;
    --remote-dir)    REMOTE_DIR="$2"; shift 2 ;;
    --compose-file)  COMPOSE_FILE="$2"; shift 2 ;;
    --force-reset)   FORCE_RESET="true"; shift ;;
    --follow-logs)   FOLLOW_LOGS="true"; shift ;;
    *) echo "[ERROR] Unknown argument: $1"; exit 1 ;;
  esac
done

# ── Validate host ──────────────────────────────────────────────────────────────
if [[ -z "${HOST_NAME}" ]]; then
  echo "[ERROR] HostName is required. Example: ./scripts/server_deploy.sh --host 1.2.3.4 --user root"
  exit 1
fi

# ── Prompt for username if not provided ───────────────────────────────────────
if [[ -z "${USER_NAME}" ]]; then
  read -rp "Server username [root]: " input_user
  if [[ -n "${input_user// /}" ]]; then
    USER_NAME="${input_user}"
  else
    USER_NAME="root"
  fi
fi

# ── Check required local commands ─────────────────────────────────────────────
if ! command -v ssh >/dev/null 2>&1; then
  echo "[ERROR] ssh command not found. Install OpenSSH Client first."
  exit 1
fi
if ! command -v scp >/dev/null 2>&1; then
  echo "[ERROR] scp command not found. Install OpenSSH Client first."
  exit 1
fi

# ── Remote deploy script (executed on the server) ─────────────────────────────
REMOTE_SCRIPT='set -Eeuo pipefail

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
  for i in $(seq 1 60); do
    status="$(docker inspect -f '"'"'{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'"'"' ai-agent-app 2>/dev/null || true)"
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
  echo "[ERROR] .env not found. I created .env from template if possible. Fill it first, then rerun deploy."
  exit 1
fi

if grep -Eq "请改成|填你的|your-" .env; then
  echo "[ERROR] .env still contains placeholder values. Edit it first: nano $REMOTE_DIR/.env"
  exit 1
fi

set_env_var() {
  key="$1"
  value="$2"
  if grep -q "^${key}=" .env; then
    sed -i "s#^${key}=.*#${key}=${value}#" .env
  else
    printf "\n%s=%s\n" "$key" "$value" >> .env
  fi
}

echo "[INFO] Normalizing host port bindings to avoid conflicts with existing services..."
set_env_var "APP_PORT" "127.0.0.1:18080"
set_env_var "FRONTEND_PORT" "127.0.0.1:14173"
set_env_var "PG_PORT" "127.0.0.1:15432"
set_env_var "REDIS_PORT" "127.0.0.1:16379"
set_env_var "FRONTEND_API_BASE_URL" ""

echo "[INFO] Removing legacy aiagent containers that may still occupy ports..."
docker rm -f aiagent-app aiagent-frontend aiagent-redis aiagent-postgres 2>/dev/null || true

echo "[INFO] Stopping current compose services before recreate..."
compose down --remove-orphans

echo "[INFO] Building and starting backend dependencies..."
if ! compose up -d --build postgres redis app; then
  echo "[ERROR] Backend services failed to start. Recent backend logs:"
  docker logs --tail=200 ai-agent-app || true
  exit 1
fi

echo "[INFO] Container status:"
compose ps

wait_for_app || exit 1

echo "[INFO] Starting frontend..."
if ! compose up -d --build frontend; then
  echo "[ERROR] Frontend failed to start. Recent frontend logs:"
  docker logs --tail=120 ai-agent-frontend || true
  exit 1
fi

echo "[INFO] Final container status:"
compose ps

echo "[INFO] Recent backend logs:"
docker logs --tail=120 ai-agent-app || true

echo "[OK] Deploy finished."

if [ "$FOLLOW_LOGS" = "true" ]; then
  echo "[INFO] Following backend logs. Press Ctrl+C to stop watching."
  docker logs -f --tail=200 ai-agent-app
fi
'

# ── Upload and execute the remote script via SSH ───────────────────────────────
TARGET="${USER_NAME}@${HOST_NAME}"
REMOTE_TEMP="/tmp/aiagent-remote-deploy-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || date +%s%N).sh"
LOCAL_TEMP="$(mktemp /tmp/aiagent-remote-deploy-XXXXXX.sh)"

printf '%s\n' "${REMOTE_SCRIPT}" > "${LOCAL_TEMP}"

REMOTE_ENV="REPO_URL='${REPO_URL}' BRANCH='${BRANCH}' REMOTE_DIR='${REMOTE_DIR}' COMPOSE_FILE='${COMPOSE_FILE}' FORCE_RESET='${FORCE_RESET}' FOLLOW_LOGS='${FOLLOW_LOGS}'"

echo "[INFO] Connecting to ${TARGET} ..."
echo "[INFO] Server IP is fixed as ${HOST_NAME}"
echo "[INFO] If asked, enter the server password."

cleanup() {
  rm -f "${LOCAL_TEMP}"
}
trap cleanup EXIT

echo "[INFO] Uploading deploy script to ${REMOTE_TEMP} ..."
scp -P "${PORT}" "${LOCAL_TEMP}" "${TARGET}:${REMOTE_TEMP}"

ssh -p "${PORT}" "${TARGET}" \
  "chmod +x '${REMOTE_TEMP}' && ${REMOTE_ENV} bash '${REMOTE_TEMP}'; code=\$?; rm -f '${REMOTE_TEMP}'; exit \$code"
