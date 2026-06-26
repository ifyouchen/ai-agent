[CmdletBinding()]
param(
    [string]$HostName = "43.155.132.161",
    [string]$User,
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

# 兼容PS7找不到系统OpenSSH的工具函数
function Get-SshBinaries {
    $systemOpenSshDir = "C:\Windows\System32\OpenSSH"
    $sshPath = Join-Path $systemOpenSshDir "ssh.exe"
    $scpPath = Join-Path $systemOpenSshDir "scp.exe"

    # 优先使用系统自带OpenSSH
    if (Test-Path $sshPath -PathType Leaf -ErrorAction SilentlyContinue) {
        return [PSCustomObject]@{
            Ssh = $sshPath
            Scp = $scpPath
        }
    }

    # 兜底从PATH查找
    $sshCmd = Get-Command ssh -ErrorAction SilentlyContinue
    $scpCmd = Get-Command scp -ErrorAction SilentlyContinue
    if ($sshCmd -and $scpCmd) {
        return [PSCustomObject]@{
            Ssh = $sshCmd.Source
            Scp = $scpCmd.Source
        }
    }

    return $null
}

# 校验必填主机
if (-not $HostName -or $HostName.Trim().Length -eq 0) {
    throw "HostName is required. Example: .\scripts\remote_deploy.ps1 -HostName 1.2.3.4 -User root"
}

# 交互输入用户名
if (-not $User -or $User.Trim().Length -eq 0) {
    $inputUser = Read-Host "Server username [root]"
    if ($inputUser -and $inputUser.Trim().Length -gt 0) {
        $User = $inputUser.Trim()
    }
    else {
        $User = "root"
    }
}

# 检测ssh/scp
$sshBin = Get-SshBinaries
if (-not $sshBin) {
    throw @"
ssh / scp 未找到，请安装 Windows OpenSSH 客户端：
设置 → 应用 → 可选功能 → 添加功能 → 搜索 OpenSSH 客户端并安装
安装完成后重启终端再执行脚本
"@
}
$ssh = $sshBin.Ssh
$scp = $sshBin.Scp

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
Write-Host "[INFO] Server IP is fixed as $HostName"
Write-Host "[INFO] If asked, enter the server password."

$localTempScript = Join-Path $env:TEMP ("aiagent-remote-deploy-" + [guid]::NewGuid().ToString("N") + ".sh")
$remoteTempScript = "/tmp/aiagent-remote-deploy-" + [guid]::NewGuid().ToString("N") + ".sh"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($localTempScript, ($remoteScript -replace "`r`n", "`n"), $utf8NoBom)

try {
    Write-Host "[INFO] Uploading deploy script to $remoteTempScript ..."
    # 修复：直接使用完整exe路径，不再调用.Source
    & $scp -P $Port $localTempScript "${target}:$remoteTempScript"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to upload remote deploy script. SCP exit code: $LASTEXITCODE"
    }

    $remoteRunCommand = "chmod +x $(Quote-Bash $remoteTempScript) && $remoteEnv bash $(Quote-Bash $remoteTempScript); code=`$?; rm -f $(Quote-Bash $remoteTempScript); exit `$code"
    & $ssh -p $Port $target $remoteRunCommand
}
finally {
    Remove-Item -LiteralPath $localTempScript -Force -ErrorAction SilentlyContinue
}

if ($LASTEXITCODE -ne 0) {
    throw "Remote deploy failed. SSH exit code: $LASTEXITCODE"
}