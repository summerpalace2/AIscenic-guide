$ErrorActionPreference = 'Stop'

$composeFile = Join-Path $PSScriptRoot 'compose.yaml'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI is unavailable. Install/start Docker Desktop, then rerun this script.'
}

docker compose version | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose is unavailable.'
}

docker compose -f $composeFile --profile cp6-it up --detach --wait --wait-timeout 90
if ($LASTEXITCODE -ne 0) {
    throw 'PostgreSQL/Redis did not become healthy. Inspect with: docker compose -f tools/cp6-infra/compose.yaml --profile cp6-it ps'
}

$postgresResult = docker compose -f $composeFile exec -T postgres psql -U cp6_test -d cp6_test -Atqc 'select 1'
if ($LASTEXITCODE -ne 0 -or $postgresResult.Trim() -ne '1') {
    throw 'PostgreSQL connectivity smoke test failed.'
}

$redisResult = docker compose -f $composeFile exec -T redis redis-cli ping
if ($LASTEXITCODE -ne 0 -or $redisResult.Trim() -ne 'PONG') {
    throw 'Redis connectivity smoke test failed.'
}

Write-Output 'CP6 integration dependencies are healthy: PostgreSQL 127.0.0.1:55432, Redis 127.0.0.1:56379.'
Write-Output 'To stop and discard these in-memory test services: docker compose -f tools/cp6-infra/compose.yaml --profile cp6-it down --remove-orphans'
