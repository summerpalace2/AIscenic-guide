param(
    [Parameter(Mandatory)][string] $TestEnvFile,
    [Parameter(Mandatory)][string] $JavaExecutable,
    [Parameter(Mandatory)][string] $ApplicationJar,
    [Parameter(Mandatory)][string] $BffRepository,
    [Parameter(Mandatory)][string] $PsqlExecutable
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'test-config.ps1')
$configuration = Read-Cp6TestConfiguration -Path $TestEnvFile
foreach ($file in @($JavaExecutable, $ApplicationJar, $PsqlExecutable)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'An explicitly supplied runtime executable or application jar is missing.' }
}
if (-not (Test-Path -LiteralPath (Join-Path $BffRepository 'server/index.mjs') -PathType Leaf)) {
    throw 'BffRepository must contain the actual server/index.mjs entrypoint.'
}
$previous = @{}
try {
    foreach ($name in $script:Cp6TestKeys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $configuration[$name], 'Process')
    }
    & node (Join-Path $PSScriptRoot 'verify-bff-cluster.mjs') --java $JavaExecutable --jar $ApplicationJar --bff $BffRepository --psql $PsqlExecutable
    if ($LASTEXITCODE -ne 0) { throw 'Actual BFF cluster acceptance failed; preserve its emitted evidence directory.' }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
