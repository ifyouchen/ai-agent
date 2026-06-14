[CmdletBinding()]
param(
    [string]$HostName,
    [string]$User = "root",
    [int]$Port = 22,
    [string]$RepoUrl = "https://github.com/ifyouchen/ai-agent.git",
    [string]$Branch = "main",
    [string]$RemoteDir = "/opt/aiagent",
    [string]$ComposeFile = "docker-compose.yml",
    [switch]$ForceReset,
    [switch]$FollowLogs
)

$ErrorActionPreference = "Stop"

function Quote-Bash {
    param([string]$Value)
    return "'" + ($Value -replace "'", "'\''") + "'"
}

if (-not $HostName) {
    $HostName = Read-Host "Server IP or domain"
}

if (-not $HostName -or $HostName.Trim().Length -eq 0) {
    throw "HostName is required. Example: .\scripts\remote_deploy.ps1 -HostName 1.2.3.4 -User root"
}

$ssh = Get-Command ssh -ErrorAction SilentlyContinue
if (-not $ssh) {
    throw "ssh command not found. Install OpenSSH Client first."
}

$forceResetValue = if ($ForceReset) { "true" } else { "false" }
$followLogsValue = if ($FollowLogs) { "true" } else { "false" }

$remoteScript = @'
set -Eeuo pipefail

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
  echo "[ERROR] .env not found. I created .env from template if possible. Fill it first, then rerun deploy."
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
'@

$remoteEnv = @(
    "REPO_URL=$(Quote-Bash $RepoUrl)",
    "BRANCH=$(Quote-Bash $Branch)",
    "REMOTE_DIR=$(Quote-Bash $RemoteDir)",
    "COMPOSE_FILE=$(Quote-Bash $ComposeFile)",
    "FORCE_RESET=$(Quote-Bash $forceResetValue)",
    "FOLLOW_LOGS=$(Quote-Bash $followLogsValue)"
) -join " "

$target = "$User@$HostName"
$remoteCommand = "$remoteEnv bash -s"

Write-Host "[INFO] Connecting to $target ..."
Write-Host "[INFO] If asked, enter the server password."
$remoteScript | & ssh -p $Port $target $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw "Remote deploy failed. SSH exit code: $LASTEXITCODE"
}
