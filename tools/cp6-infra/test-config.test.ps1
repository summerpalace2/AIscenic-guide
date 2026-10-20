$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'test-config.ps1')
$directory = Join-Path ([IO.Path]::GetTempPath()) ('CP6 配置测试-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
$file = Join-Path $directory '.env.cp6-test'
$valid = @'
# Literal test values only; never real credentials.
CP6_IT_ENABLE=true
CP6_IT_PERMISSION=DISPOSABLE_CP6_TEST_ONLY
CP6_IT_PG_URL=jdbc:postgresql://127.0.0.1:55432/cp6_test
CP6_IT_PG_DATABASE=cp6_test
CP6_IT_PG_USER=cp6_test
CP6_IT_PG_PASSWORD='literal=$HOME;$(throw bad)'
CP6_IT_REDIS_HOST=127.0.0.1
CP6_IT_REDIS_PORT=56379
'@
function Reject-Configuration([string] $Contents, [string] $Label) {
    [IO.File]::WriteAllText($file, $Contents)
    $rejected = $false
    try { $null = Read-Cp6TestConfiguration -Path $file } catch {
        $rejected = $true
        if ($_.Exception.Message.Contains('literal=')) { throw 'Configuration error leaked a value.' }
    }
    if (-not $rejected) { throw "Expected rejection: $Label" }
}
[IO.File]::WriteAllText($file, $valid, [Text.UTF8Encoding]::new($true))
$config = Read-Cp6TestConfiguration -Path $file
if ($config['CP6_IT_PG_PASSWORD'] -cne 'literal=$HOME;$(throw bad)') { throw 'Credential literal changed or executed.' }
Reject-Configuration ($valid + "`nCP6_IT_REDIS_HOST=other") 'duplicate'
Reject-Configuration ($valid + "`nJWT_SECRET=unrelated") 'application configuration'
Reject-Configuration ($valid + "`nCP6_IT_UNKNOWN=value") 'unknown key'
Reject-Configuration ($valid.Replace('CP6_IT_ENABLE=true', 'CP6_IT_ENABLE=false')) 'disabled opt-in'
Reject-Configuration ($valid.Replace('DISPOSABLE_CP6_TEST_ONLY', 'production')) 'missing test acknowledgement'
Reject-Configuration ($valid.Replace('CP6_IT_PG_USER=cp6_test', '')) 'missing required key'
Reject-Configuration ($valid + "`nCP6_IT_REDIS_TLS=maybe") 'invalid TLS'
Reject-Configuration ($valid.Replace("'literal=`$HOME;`$(throw bad)'", "'literal=unclosed")) 'unclosed quote'
Write-Output 'PASS: 9 dedicated configuration parser cases (literal/BOM/path, duplicates, application keys, unknown keys, opt-in, permission, required key, TLS, quotes).'
