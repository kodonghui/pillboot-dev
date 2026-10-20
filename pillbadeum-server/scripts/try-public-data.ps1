[CmdletBinding()]
param([switch]$UseStoredKey)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$serverPath = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $serverPath '.env'
$stateDirectory = Join-Path $serverPath 'build/local-probes'
$statePath = Join-Path $stateDirectory 'public-data.json'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$exitCode = 1

# 결과에는 제공자 번호·상태·숫자 필드만 저장한다. 키와 원문 응답은 기록하지 않는다.
function Publish-State {
    param([string]$Phase, [array]$Results = @(), $ExitCode = $null)
    [System.IO.Directory]::CreateDirectory($stateDirectory) | Out-Null
    $state = [ordered]@{
        phase = $Phase
        updated_at_utc = [DateTimeOffset]::UtcNow.ToString('o')
        results = @($Results)
        exit_code = $ExitCode
    }
    [System.IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 5), $utf8)
}

Push-Location -LiteralPath $serverPath
try {
    & git check-ignore -q .env
    if ($LASTEXITCODE -ne 0) { throw 'The local key file must be ignored by Git.' }
    if (-not $UseStoredKey) {
        Write-Host '공공데이터포털 일반 인증키(Decoding)를 입력하고 Enter를 누르세요.' -ForegroundColor Cyan
        Write-Host '입력은 가려지며 기존 .env의 다른 설정은 보존합니다.'
        do {
            $secretInput = Read-Host '인증키' -AsSecureString
            $keyPointer = [IntPtr]::Zero
            try {
                $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secretInput)
                $plainKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
            } finally {
                if ($keyPointer -ne [IntPtr]::Zero) {
                    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer)
                }
                $secretInput.Dispose()
            }
            $plainKey = ($plainKey -replace '^\s*PUBLIC_DATA_SERVICE_KEY\s*=\s*', '').Trim().Trim([char[]]@(34, 39))
            if ($plainKey -match '%[0-9A-Fa-f]{2}') { $plainKey = [Uri]::UnescapeDataString($plainKey) }
            $validInput = $plainKey -cmatch '^[A-Za-z0-9+/=_-]{20,2048}$'
            if (-not $validInput) {
                $plainKey = $null
                Write-Host '주소 전체가 아닌 키 값만 입력해 주세요.' -ForegroundColor Yellow
            }
        } until ($validInput)

        $existingLines = if (Test-Path -LiteralPath $envPath) {
            [System.IO.File]::ReadAllLines($envPath, $utf8)
        } else { @() }
        $updatedLines = [System.Collections.Generic.List[string]]::new()
        $keyWritten = $false
        foreach ($existingLine in $existingLines) {
            if ($existingLine -match '^\s*PUBLIC_DATA_SERVICE_KEY\s*=') {
                if (-not $keyWritten) {
                    $updatedLines.Add('PUBLIC_DATA_SERVICE_KEY=' + $plainKey)
                    $keyWritten = $true
                }
            } else { $updatedLines.Add($existingLine) }
        }
        if (-not $keyWritten) { $updatedLines.Add('PUBLIC_DATA_SERVICE_KEY=' + $plainKey) }
        [System.IO.File]::WriteAllLines($envPath, $updatedLines, $utf8)
        $plainKey = $null
        $updatedLines = $null
        $existingLines = $null
    } elseif (-not (Test-Path -LiteralPath $envPath)) {
        throw 'The local key file is missing.'
    }

    # 이 PowerShell 환경에서는 SetEnvironmentVariable(..., $null)이 빈 값을 남겼으므로 항목 자체를 제거한다.
    Remove-Item -LiteralPath Env:PUBLIC_DATA_SERVICE_KEY -ErrorAction SilentlyContinue
    if ($env:JAVA_HOME) {
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
    }
    Publish-State 'running'
    Write-Host '저장된 키로 공공데이터 6개 경로를 조회 중입니다...' -ForegroundColor Cyan

    # 원문 출력을 가로채고 허용한 필드만 아래에서 표시한다.
    $probeOutput = & .\gradlew.bat tryChatProviders --args=public-data --no-daemon --console=plain 2>&1
    $exitCode = $LASTEXITCODE
    $results = [System.Collections.Generic.List[object]]::new()
    $allowedCodes = @(
        'SERVICE_KEY_IS_NOT_REGISTERED_ERROR', 'SERVICE_ACCESS_DENIED_ERROR', 'SERVICE_KEY_IS_NULL',
        'INVALID_REQUEST_PARAMETER_ERROR', 'NO_OPENAPI_SERVICE_ERROR', 'PERMISSION_DENIED',
        'LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR', 'unknown'
    )
    foreach ($outputLine in $probeOutput) {
        $lineText = $outputLine.ToString()
        if ($lineText -match '^provider=(public-data-[1-6]) result=(SUCCESS|FAILED)(?:\s|$)') {
            $row = [ordered]@{ provider = $Matches[1]; result = $Matches[2] }
            foreach ($field in @('elapsedMs', 'rows', 'totalCount', 'httpStatus')) {
                if ($lineText -match ('(?:^|\s)' + $field + '=(\d+)(?:\s|$)')) {
                    $row[$field] = [long]$Matches[1]
                }
            }
            if ($lineText -match '(?:^|\s)providerCode=([A-Z_]+|unknown)(?:\s|$)' -and $allowedCodes -contains $Matches[1]) {
                $row['providerCode'] = $Matches[1]
            }
            $results.Add([pscustomobject]$row)
            Write-Host (($row.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ' ')
        }
    }
    $probeOutput = $null
    if ($results.Count -ne 6) {
        $exitCode = 1
        Publish-State 'probe_error' @($results.ToArray()) $exitCode
        Write-Host '6개 결과를 받지 못했습니다. Java 21과 .env 키 설정을 확인해 주세요.' -ForegroundColor Yellow
    } else {
        Publish-State 'completed' @($results.ToArray()) $exitCode
        $successCount = @($results | Where-Object { $_.result -eq 'SUCCESS' }).Count
        Write-Host ("진단 완료: 성공 {0}/6. 결과: build/local-probes/public-data.json" -f $successCount)
    }
} catch {
    $plainKey = $null
    $exitCode = 1
    Publish-State 'setup_error' @() $exitCode
    Write-Host '진단 준비 중 오류가 발생했습니다. 키 값은 출력하지 않았습니다.' -ForegroundColor Red
} finally {
    Pop-Location
}
exit $exitCode
