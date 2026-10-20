# Only explicitly selected, dedicated integration configuration is accepted.
# Values are data: no expansion, dot-sourcing, or application .env fallback.
$script:Cp6TestKeys = @(
    'CP6_IT_ENABLE', 'CP6_IT_PERMISSION', 'CP6_IT_PG_URL', 'CP6_IT_PG_DATABASE',
    'CP6_IT_PG_USER', 'CP6_IT_PG_PASSWORD', 'CP6_IT_REDIS_HOST', 'CP6_IT_REDIS_PORT',
    'CP6_IT_REDIS_USER', 'CP6_IT_REDIS_PASSWORD', 'CP6_IT_REDIS_TLS'
)

function Read-Cp6TestConfiguration {
    param([Parameter(Mandatory)][string] $Path)
    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($file.PSIsContainer) { throw 'Test configuration must be a file.' }
    $values = @{}
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadAllLines($file.FullName)) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^\s*(CP6_IT_[A-Z_]+)\s*=(.*)$') {
            throw "Invalid dedicated test configuration at line $lineNumber; only CP6_IT_* assignments are allowed."
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($script:Cp6TestKeys -cnotcontains $name -or $values.ContainsKey($name)) {
            throw "Unknown or duplicate test configuration key at line $lineNumber."
        }
        if ($value.StartsWith('"') -or $value.StartsWith("'")) {
            if ($value.Length -lt 2 -or $value[$value.Length - 1] -ne $value[0]) {
                throw "Unclosed quoted test configuration at line $lineNumber."
            }
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$name] = $value
    }
    foreach ($name in @('CP6_IT_ENABLE', 'CP6_IT_PERMISSION', 'CP6_IT_PG_URL', 'CP6_IT_PG_DATABASE',
            'CP6_IT_PG_USER', 'CP6_IT_PG_PASSWORD', 'CP6_IT_REDIS_HOST', 'CP6_IT_REDIS_PORT')) {
        if (-not $values.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($values[$name])) {
            throw "Missing required dedicated test key: $name"
        }
    }
    if ($values['CP6_IT_ENABLE'] -cne 'true' -or $values['CP6_IT_PERMISSION'] -cne 'DISPOSABLE_CP6_TEST_ONLY') {
        throw 'Test configuration must explicitly enable and acknowledge disposable test services.'
    }
    if ($values.ContainsKey('CP6_IT_REDIS_TLS') -and $values['CP6_IT_REDIS_TLS'] -cnotin @('true', 'false')) {
        throw 'CP6_IT_REDIS_TLS must be true or false.'
    }
    return $values
}
