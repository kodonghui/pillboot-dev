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
& $mysqlExecutable "--host=$MysqlHost" "--port=$MysqlPort" "--user=$AdminUser" `
    '--password' '--default-character-set=utf8mb4' "--execute=$sqlText"
if ($LASTEXITCODE -ne 0) {
    throw "Database setup failed (mysql exit code $LASTEXITCODE). Check the error above."
}

Write-Host ''
Write-Host 'Database/account/schema setup completed. Existing data and passwords were preserved.'
Write-Host 'This script creates missing objects only; it does not upgrade existing columns or constraints.'
Write-Host 'For the original 10-table database, read sql/README.md and apply the explicit 03 upgrade after backup.'
Write-Host 'Copy .env.example to .env if needed, then fill DB_USERNAME, DB_PASSWORD and JWT_SECRET.'
Write-Host 'Run .\gradlew.bat bootRun from the project directory.'
Write-Host 'Sample data is optional; use -IncludeTestData only on a fresh empty development database.'
