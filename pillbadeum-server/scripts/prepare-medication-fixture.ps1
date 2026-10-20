#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Email,
    [switch]$InspectOnly,
    [switch]$Reset,
    [switch]$LocalFileDb,
    [string]$DriverJar
)
$ErrorActionPreference = 'Stop'
if ($InspectOnly -and $Reset) { throw 'InspectOnly and Reset cannot be combined.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $DriverJar) {
    $driverRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j'
    $driver = Get-ChildItem -LiteralPath $driverRoot -Filter '*.jar' -Recurse |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $driver) { throw 'Run gradlew test bootJar once to cache the MySQL driver, or pass -DriverJar.' }
    $DriverJar = $driver.FullName
}
function ConvertTo-NativeArgument([string]$Value) {
    # Windows의 argv 인용 규칙. cmd.exe나 PowerShell을 다시 거치지 않고 Java에 전달한다.
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
$launch = [System.Diagnostics.ProcessStartInfo]::new()
$launch.FileName = (Get-Command java).Source
$launch.WorkingDirectory = $projectRoot
$launch.UseShellExecute = $false
$launch.CreateNoWindow = $true
$launch.RedirectStandardOutput = $true
$launch.RedirectStandardError = $true
$nativeArguments = @('--class-path', $DriverJar, (Join-Path $PSScriptRoot 'PrepareMedicationFixture.java'), '--email', $Email)
if (-not $InspectOnly) { $nativeArguments += '--apply' }
if ($Reset) { $nativeArguments += '--reset' }
$launch.Arguments = ($nativeArguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
if ($LocalFileDb) {
    # 자식 Java만 .env의 DB 설정을 사용한다. IDE/사용자 환경변수는 변경하지 않는다.
    foreach ($key in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) { $launch.EnvironmentVariables.Remove($key) }
}
$process = [System.Diagnostics.Process]::Start($launch)
$outputTask = $process.StandardOutput.ReadToEndAsync()
$errorTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$outputText = $outputTask.GetAwaiter().GetResult()
$errorText = $errorTask.GetAwaiter().GetResult()
if ($outputText) { Write-Output $outputText.TrimEnd() }
if ($errorText) { [Console]::Error.WriteLine($errorText.TrimEnd()) }
if ($process.ExitCode -ne 0) { throw 'Fixture preparation failed. Changes before COMMITTED were rolled back.' }
$reportFolder = Join-Path $projectRoot 'build'
[void](New-Item -ItemType Directory -Path $reportFolder -Force)
$outputText | Set-Content -LiteralPath (Join-Path $reportFolder 'medication-fixture.txt') -Encoding utf8
