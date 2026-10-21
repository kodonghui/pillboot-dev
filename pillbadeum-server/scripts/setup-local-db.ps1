[CmdletBinding()]
param(
    [string]$MysqlHost = 'localhost',
    [ValidateRange(1, 65535)]
    [int]$MysqlPort = 3306,
    [string]$AdminUser = 'root',
    [string]$MySqlClient,
    [switch]$IncludeTestData
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sqlFiles = @('00_PILLBADEUM_DB_CREAT.sql', '01_PILLBADEUM_DB_SCRIPT.sql')
if ($IncludeTestData) { $sqlFiles += '02_PILLBADEUM_TEST_DATA.sql' }
$sqlFiles += '03_MEDICATION_UPDATE.sql'
$sqlFiles += '04_MY_DRUG_COMPOSITE_UNIQUE.sql'
$sqlFiles += '05_PHOTO_CANDIDATE_LINKS.sql'
$sqlFiles += '06_DRUG_CACHE_METADATA.sql'
$sqlFiles += '07_DOSE_CONFIRMATION.sql'
$sqlFiles += '08_CHAT_DUR_SNAPSHOT.sql'
$sqlFiles += '09_VECTOR_CLEANUP.sql'
$sqlFiles += '10_PILL_IDENTIFICATION_SESSION.sql'
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
    throw 'Medication SQL was BLOCKED. Check the precheck output and sql/README.md before retrying.'
}

Write-Host ''
Write-Host 'Database/account/schema setup completed. Existing data and passwords were preserved.'
Write-Host '03_MEDICATION_UPDATE.sql applies medication changes and skips changes already applied. Check that its final status is READY.'
Write-Host '04_MY_DRUG_COMPOSITE_UNIQUE.sql adds pair uniqueness. Its final my_drug_unique_status must also be READY.'
Write-Host '05_PHOTO_CANDIDATE_LINKS.sql connects candidate codes and front/back photos. Its final photo_links_status must be READY.'
Write-Host '06-09 add metadata, dose confirmation, DUR snapshots and durable vector cleanup. Each final status must be READY.'
Write-Host '10 adds expiring pill identification results. Existing drug, photo and source columns stay compatible.'
Write-Host 'Use an empty development database for the ERD Cloud schema. For existing databases, read sql/README.md first.'
Write-Host 'Copy .env.example to .env if needed, then fill DB_USERNAME, DB_PASSWORD and JWT_SECRET.'
Write-Host 'Run .\gradlew.bat bootRun from the project directory.'
Write-Host 'Sample data is optional; use -IncludeTestData only on a fresh empty development database.'
