$localEnvFile = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $localEnvFile) {
    foreach ($line in Get-Content -LiteralPath $localEnvFile) {
        if ($line -match '^\s*(DB_URL|DB_USER|DB_PASSWORD|SERVER_ADDRESS|SERVER_PORT|GAME_DEMO_SEED_ENABLED|WS_ALLOWED_ORIGINS)=(.*)$') {
            $variableName = $Matches[1]
            $variableValue = $Matches[2].Trim()
            if (-not [Environment]::GetEnvironmentVariable($variableName, 'Process')) {
                [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
            }
        }
    }
}
