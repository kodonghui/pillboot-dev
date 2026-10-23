[CmdletBinding()]
param(
    [string]$MysqlHost = 'localhost',
    [ValidateRange(1, 65535)]
    [int]$MysqlPort = 3306,
    [string]$AdminUser = 'root',
    [string]$MySqlClient,
    [switch]$IncludeTestData,
    [switch]$UpgradeExisting
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($UpgradeExisting -and $IncludeTestData) {
    throw 'Sample data is only for a fresh empty database. Do not combine -UpgradeExisting with -IncludeTestData.'
}
$sqlFiles = @('00_PILLBADEUM_DB_CREAT.sql')
if ($UpgradeExisting) {
    # Existing databases keep their data; apply only the versioned changes.
    $sqlFiles += @('03_MEDICATION_UPDATE.sql', '04_MY_DRUG_COMPOSITE_UNIQUE.sql',
        '05_PHOTO_CANDIDATE_LINKS.sql', '06_DRUG_CACHE_METADATA.sql', '07_DOSE_CONFIRMATION.sql',
        '08_CHAT_DUR_SNAPSHOT.sql', '09_VECTOR_CLEANUP.sql', '10_PILL_IDENTIFICATION_SESSION.sql',
        '11_DRUG_INGREDIENT_LENGTH.sql', '12_DRUG_INGREDIENT_TEXT.sql')
} else {
    # New databases start with the current TEXT schema and required signup policies.
    $sqlFiles += '01_PILLBADEUM_DB_SCRIPT.sql'
}
$sqlFiles += '13_BASE_CONSENT.sql'
if ($IncludeTestData) { $sqlFiles += '02_PILLBADEUM_TEST_DATA.sql' }
$sqlPaths = $sqlFiles | ForEach-Object { Join-Path $projectRoot "sql/$_" }
foreach ($sqlPath in $sqlPaths) {
    if (-not (Test-Path -LiteralPath $sqlPath -PathType Leaf)) {
        throw "SQL file not found: $sqlPath"
    }
}

if ($MySqlClient) {
    $mysqlExecutable = (Resolve-Path -LiteralPath $MySqlClient).Path
    if (-not (Test-Path -LiteralPath $mysqlExecutable -PathType Leaf)) {
        throw "MySQL client is not a file: $mysqlExecutable"
    }
} else {
    $mysqlCommand = Get-Command 'mysql', 'mysql.exe' -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($mysqlCommand) {
        $mysqlExecutable = $mysqlCommand.Source
    } else {
        $mysqlExecutable = $null
        if ($env:ProgramFiles) {
            $installationRoot = Join-Path $env:ProgramFiles 'MySQL'
            if (Test-Path -LiteralPath $installationRoot -PathType Container) {
                $installations = Get-ChildItem -LiteralPath $installationRoot -Directory -Filter 'MySQL Server*' |
                    Sort-Object Name -Descending
                foreach ($installation in $installations) {
                    $candidate = Join-Path $installation.FullName 'bin\mysql.exe'
                    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                        $mysqlExecutable = $candidate
                        break
                    }
                }
            }
        }
        if (-not $mysqlExecutable) {
            throw 'mysql.exe was not found. Install the MySQL client, use -MySqlClient with its full path, or open sql/00_PILLBADEUM_DB_CREAT.sql in Workbench and run it as root.'
        }
    }
}

$sqlText = ($sqlPaths | ForEach-Object {
    [System.IO.File]::ReadAllText($_, [System.Text.Encoding]::UTF8)
}) -join "`n"
$sqlText += @'

SELECT IF(
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE())=18
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE())=146
    AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
         AND table_name='drug' AND column_name='material' AND data_type='text' AND is_nullable='NO')=1,
    'READY','BLOCKED') AS current_schema_status;
'@
Write-Host "Preparing pilldb on ${MysqlHost}:${MysqlPort}."
Write-Host "Enter the MySQL password for '$AdminUser' at the prompt. The script does not store it."

# Native password prompt: do not put the administrator password in arguments or files.
$setupOutput = & $mysqlExecutable "--host=$MysqlHost" "--port=$MysqlPort" "--user=$AdminUser" `
    '--password' '--default-character-set=utf8mb4' '--batch' "--execute=$sqlText"
$setupOutput | ForEach-Object { Write-Output $_ }
if ($LASTEXITCODE -ne 0) {
    throw "Database setup failed (mysql exit code $LASTEXITCODE). Check the error above."
}
if ($setupOutput -contains 'BLOCKED') {
    throw 'Schema is BLOCKED. For an existing database use -UpgradeExisting; check the output and sql/README.md.'
}

Write-Host ''
Write-Host 'Current schema is ready: 18 tables, 146 columns, drug.material TEXT; signup policies ready.'
if ($UpgradeExisting) {
    Write-Host 'Applied changes 03-12 and signup policies 13. No sample accounts or drugs were inserted.'
} else {
    Write-Host 'Created the current schema using 00, 01 and signup policies 13. Changes 03-12 are not needed for a new database.'
}
Write-Host 'Copy .env.example to .env if needed, then fill DB_USERNAME, DB_PASSWORD and JWT_SECRET.'
Write-Host 'Run .\gradlew.bat bootRun from the project directory.'
Write-Host 'Sample data is optional; use -IncludeTestData only on a fresh empty development database.'
