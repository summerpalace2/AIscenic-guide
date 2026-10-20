param([string] $TestEnvFile, [switch] $Http,
    [string] $JavaExecutable, [string] $ApplicationJar, [string] $PsqlExecutable)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'test-config.ps1')
$selectedConfiguration = if ($TestEnvFile) { Read-Cp6TestConfiguration -Path $TestEnvFile } else { $null }
if ($Http) {
    foreach ($path in @($JavaExecutable, $ApplicationJar, $PsqlExecutable)) {
        if (-not $path -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw 'HTTP acceptance requires explicit existing JavaExecutable, ApplicationJar and PsqlExecutable paths.'
        }
    }
}
$previousEnvironment = @{}
$repository = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('scenic-cp6-infra-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory | Out-Null
$log = Join-Path $runDirectory 'maven.log'

# No implicit enable flag, credentials, service startup, or application .env discovery.
Push-Location $repository
try {
    if ($null -ne $selectedConfiguration) {
        foreach ($name in $script:Cp6TestKeys) {
            $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $selectedConfiguration[$name], 'Process')
        }
    }
    & (Join-Path $repository 'mvnw.cmd') -pl ai test '-Dtest=Cp6InfrastructureTargetTest,Cp6RealInfrastructureIntegrationTest' '-DfailIfNoTests=true' 2>&1 |
        Tee-Object -FilePath $log
    $mavenExit = $LASTEXITCODE
    if ($mavenExit -ne 0) { throw "Real infrastructure probe failed. Log: $log" }
    $reportPath = Join-Path $repository 'ai/target/surefire-reports/TEST-com.ai.guide.infrastructure.planner.proposal.Cp6RealInfrastructureIntegrationTest.xml'
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $runDirectory 'integration-junit.xml')
    [xml] $report = Get-Content -LiteralPath $reportPath -Raw
    $suite = $report.testsuite
    if ([int] $suite.tests -lt 7 -or [int] $suite.skipped -ne 0) {
        Write-Warning "NOT ACCEPTED: real-service tests were skipped or missing. Log and JUnit: $runDirectory"
        exit 2
    }
    if ([int] $suite.failures -ne 0 -or [int] $suite.errors -ne 0) { throw 'JUnit contains failures/errors.' }
    Write-Output "Repository-level real infrastructure probes passed. Evidence: $runDirectory"
    Write-Output 'This does not certify HTTP two-instance business flows or PostgreSQL/Redis process crash recovery.'
    if ($Http) {
        & python (Join-Path $PSScriptRoot 'verify-http-instances.py') --java $JavaExecutable --jar $ApplicationJar --psql $PsqlExecutable
        if ($LASTEXITCODE -ne 0) { throw 'Actual two-instance HTTP acceptance failed.' }
    }
} finally {
    Pop-Location
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
